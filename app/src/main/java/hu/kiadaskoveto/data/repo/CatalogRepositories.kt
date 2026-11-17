package hu.kiadaskoveto.data.repo

import androidx.room.withTransaction
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.CategoryMappingEntity
import hu.kiadaskoveto.database.entity.CategoryRuleEntity
import hu.kiadaskoveto.database.entity.StoreEntity
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.domain.Store
import hu.kiadaskoveto.parser.categorize.CategorizationContext
import hu.kiadaskoveto.parser.categorize.CategoryKeys
import hu.kiadaskoveto.parser.categorize.DefaultCategories
import hu.kiadaskoveto.parser.categorize.RuleMatchType
import hu.kiadaskoveto.parser.categorize.UserCategoryMapping
import hu.kiadaskoveto.parser.categorize.UserCategoryRule
import hu.kiadaskoveto.ui.theme.CategoryVisuals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CategoryRepository(private val db: KiadasDatabase) {

    private val dao = db.categoryDao()

    val categories: Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Inserts the 17 default Hungarian categories on first launch (idempotent). */
    suspend fun ensureDefaults() {
        val now = System.currentTimeMillis()
        dao.insertAllIgnore(DefaultCategories.ALL.map {
            CategoryEntity(
                key = it.key, name = it.name, colorArgb = CategoryVisuals.defaultColors[it.key] ?: 0xFF5F7A70,
                iconKey = it.key, sortOrder = it.sortOrder, isSystem = true, createdAt = now,
            )
        })
    }

    suspend fun all(): List<Category> = dao.all().map { it.toDomain() }
    suspend fun byId(id: Long): Category? = dao.byId(id)?.toDomain()
    suspend fun byKey(key: String): Category? = dao.byKey(key)?.toDomain()
    suspend fun uncategorizedId(): Long = dao.byKey(CategoryKeys.UNCATEGORIZED)?.id ?: run { ensureDefaults(); dao.byKey(CategoryKeys.UNCATEGORIZED)!!.id }

    suspend fun create(name: String, parentId: Long?, colorArgb: Long, iconKey: String): Long {
        require(name.isNotBlank()) { "A kategória neve nem lehet üres." }
        return dao.insert(
            CategoryEntity(
                key = "user-" + UUID.randomUUID(), name = name.trim(), parentId = parentId, colorArgb = colorArgb, iconKey = iconKey,
                sortOrder = dao.nextSortOrder(), isSystem = false, createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun update(category: Category) {
        val e = dao.byId(category.id) ?: return
        require(category.name.isNotBlank()) { "A kategória neve nem lehet üres." }
        dao.update(e.copy(name = category.name.trim(), colorArgb = category.colorArgb, iconKey = category.iconKey, parentId = category.parentId, sortOrder = category.sortOrder))
    }

    /** Deletes a category; its items, products and subcategories' items move to "Nem besorolt". */
    suspend fun delete(id: Long) {
        val e = dao.byId(id) ?: return
        require(e.key != CategoryKeys.UNCATEGORIZED) { "A „Nem besorolt” kategória nem törölhető." }
        db.withTransaction {
            val target = uncategorizedId()
            val ids = listOf(id) + dao.childIds(id)
            dao.moveItems(ids, target)
            dao.moveProducts(ids, target)
            dao.delete(id)
        }
    }
}

class StoreRepository(private val db: KiadasDatabase) {
    private val dao = db.storeDao()

    val stores: Flow<List<Store>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    val storesWithStats = dao.observeWithStats()

    suspend fun all(): List<Store> = dao.all().map { it.toDomain() }
    suspend fun byId(id: Long): Store? = dao.byId(id)?.toDomain()

    /** Finds a store by (normalized) name or creates it. Chain-recognized stores share the chain name. */
    suspend fun resolve(name: String?, chainKey: String?, address: String?, taxNumber: String?): Long? {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = HungarianText.matchKey(clean)
        dao.byNormalizedName(key)?.let { existing ->
            if ((existing.address == null && address != null) || (existing.chainKey == null && chainKey != null)) {
                dao.update(existing.copy(address = existing.address ?: address, chainKey = existing.chainKey ?: chainKey, taxNumber = existing.taxNumber ?: taxNumber))
            }
            return existing.id
        }
        return dao.insert(StoreEntity(name = clean, normalizedName = key, chainKey = chainKey, address = address, taxNumber = taxNumber, createdAt = System.currentTimeMillis()))
    }

    suspend fun rename(id: Long, name: String) {
        val e = dao.byId(id) ?: return
        val key = HungarianText.matchKey(name)
        val clash = dao.byNormalizedName(key)
        if (clash != null && clash.id != id) {
            // Renaming onto an existing store merges them.
            db.withTransaction { dao.moveReceipts(id, clash.id); dao.delete(id) }
        } else dao.update(e.copy(name = name.trim(), normalizedName = key))
    }

    suspend fun delete(id: Long) = dao.delete(id)
}

/**
 * Remembers user corrections and rules, and produces the snapshot the deterministic
 * categorizer uses. Store keys are store ids as strings; scope 0 means "any store".
 */
class LearningRepository(private val db: KiadasDatabase) {
    private val rules = db.ruleDao()
    private val categories = db.categoryDao()

    val rulesFlow = rules.observeRules()
    val mappingsFlow = rules.observeMappings()

    suspend fun context(): CategorizationContext {
        val keyById = categories.all().associate { it.id to it.key }
        return CategorizationContext(
            mappings = rules.allMappings().mapNotNull { m ->
                keyById[m.categoryId]?.let { UserCategoryMapping(m.matchKey, m.storeScope.takeIf { it != 0L }?.toString(), it) }
            },
            rules = rules.allRules().filter { it.enabled }.mapNotNull { r ->
                val type = runCatching { RuleMatchType.valueOf(r.matchType) }.getOrNull() ?: return@mapNotNull null
                keyById[r.categoryId]?.let { UserCategoryRule(r.pattern, type, it, r.storeId?.toString(), r.priority) }
            },
        )
    }

    /** Records "this description means this category" (store-specific when the store is known). */
    suspend fun rememberCategory(rawName: String, storeId: Long?, categoryId: Long) {
        val key = HungarianText.matchKey(rawName)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        val scope = storeId ?: 0L
        val existing = rules.mapping(key, scope)
        rules.upsertMapping(existing?.copy(categoryId = categoryId, updatedAt = now) ?: CategoryMappingEntity(matchKey = key, storeScope = scope, categoryId = categoryId, createdAt = now, updatedAt = now))
    }

    suspend fun deleteMapping(id: Long) = rules.deleteMapping(id)

    suspend fun addRule(pattern: String, type: RuleMatchType, categoryId: Long, storeId: Long?, priority: Int): Long {
        require(pattern.isNotBlank()) { "A minta nem lehet üres." }
        if (type == RuleMatchType.REGEX) runCatching { Regex(pattern) }.getOrElse { throw IllegalArgumentException("Érvénytelen reguláris kifejezés.") }
        return rules.insertRule(CategoryRuleEntity(pattern = pattern.trim(), matchType = type.name, categoryId = categoryId, storeId = storeId, priority = priority, createdAt = System.currentTimeMillis()))
    }

    suspend fun updateRule(rule: CategoryRuleEntity) = rules.updateRule(rule)
    suspend fun deleteRule(id: Long) = rules.deleteRule(id)
}
