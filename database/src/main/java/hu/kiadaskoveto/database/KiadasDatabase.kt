package hu.kiadaskoveto.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import hu.kiadaskoveto.database.dao.AnalyticsDao
import hu.kiadaskoveto.database.dao.BackupDao
import hu.kiadaskoveto.database.dao.BudgetDao
import hu.kiadaskoveto.database.dao.CategoryDao
import hu.kiadaskoveto.database.dao.ProductDao
import hu.kiadaskoveto.database.dao.ReceiptDao
import hu.kiadaskoveto.database.dao.RuleDao
import hu.kiadaskoveto.database.dao.StoreDao
import hu.kiadaskoveto.database.entity.BudgetAlertEntity
import hu.kiadaskoveto.database.entity.BudgetEntity
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.CategoryMappingEntity
import hu.kiadaskoveto.database.entity.CategoryRuleEntity
import hu.kiadaskoveto.database.entity.ProductAliasEntity
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.entity.StoreEntity

@Database(
    entities = [
        CategoryEntity::class, StoreEntity::class, ReceiptEntity::class, ReceiptItemEntity::class,
        ReceiptDocumentEntity::class, ProductEntity::class, ProductAliasEntity::class,
        CategoryMappingEntity::class, CategoryRuleEntity::class, BudgetEntity::class, BudgetAlertEntity::class,
    ],
    version = KiadasDatabase.VERSION,
    exportSchema = true,
)
abstract class KiadasDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun categoryDao(): CategoryDao
    abstract fun storeDao(): StoreDao
    abstract fun productDao(): ProductDao
    abstract fun ruleDao(): RuleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 1
        const val NAME = "kiadaskoveto.db"

        /**
         * @param openHelperFactory optional factory, e.g. SQLCipher's for an encrypted database.
         */
        fun build(
            context: Context,
            name: String = NAME,
            openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
        ): KiadasDatabase = Room.databaseBuilder(context.applicationContext, KiadasDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
            .build()

        fun inMemory(context: Context): KiadasDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, KiadasDatabase::class.java).build()
    }
}

/**
 * Schema migrations. The exported schemas in `database/schemas/` are the source of truth;
 * every version bump must add a [Migration] here (no destructive fallback is configured, so a
 * missing migration fails loudly instead of silently deleting user data).
 *
 * Add new migrations like:
 * ```
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE receipts ADD COLUMN currency TEXT NOT NULL DEFAULT 'HUF'")
 *     }
 * }
 * ```
 * and cover it with a MigrationTestHelper test.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
