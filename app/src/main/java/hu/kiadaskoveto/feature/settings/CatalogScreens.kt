package hu.kiadaskoveto.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.feature.receipts.CategoryPickerSheet
import hu.kiadaskoveto.parser.categorize.RuleMatchType
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.CategoryVisuals
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, onOpenRules: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val categories by c.categories.categories.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Category?>(null) }
    var creatingParent by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kategóriák") }, navigationIcon = { BackIcon(onBack) }, actions = {
            IconButton(onClick = onOpenRules) { Icon(Icons.Outlined.Rule, contentDescription = "Szabályok") }
        }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creatingParent = null; creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Új kategória") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 104.dp)) {
            categories.filter { it.parentId == null }.forEach { root ->
                item(key = root.id) {
                    ListRow(root.name, subtitle = if (root.isSystem) "Alapértelmezett kategória" else "Saját kategória", leading = { CategoryBadge(root, 40.dp) },
                        trailing = {
                            Row {
                                IconButton(onClick = { creatingParent = root.id; creating = true }) { Icon(Icons.Outlined.Add, contentDescription = "Alkategória hozzáadása") }
                                if (!root.isUncategorized) IconButton(onClick = { deleting = root }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Törlés") }
                            }
                        }, onClick = { editing = root })
                }
                items(categories.filter { it.parentId == root.id }, key = { it.id }) { child ->
                    ListRow(child.name, subtitle = "Alkategória", modifier = Modifier.padding(start = Spacing.xxl), leading = { CategoryBadge(child, 32.dp) },
                        trailing = { IconButton(onClick = { deleting = child }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Törlés") } },
                        onClick = { editing = child })
                }
            }
        }
    }
    if (creating) CategoryEditor(null, creatingParent, onDismiss = { creating = false }, onSave = { name, color, icon ->
        scope.launch { runCatching { c.categories.create(name, creatingParent, color, icon) }.onFailure { snackbar.showSnackbar(it.message ?: "Hiba") }; creating = false }
    })
    editing?.let { cat ->
        CategoryEditor(cat, cat.parentId, onDismiss = { editing = null }, onSave = { name, color, icon ->
            scope.launch { runCatching { c.categories.update(cat.copy(name = name, colorArgb = color, iconKey = icon)) }.onFailure { snackbar.showSnackbar(it.message ?: "Hiba") }; editing = null }
        })
    }
    deleting?.let { cat ->
        ConfirmDialog("Kategória törlése", "„${cat.name}” törlődik. A hozzá tartozó tételek és termékek a „Nem besorolt” kategóriába kerülnek; alkategóriái is törlődnek.", "Törlés",
            onConfirm = { scope.launch { runCatching { c.categories.delete(cat.id) }.onFailure { snackbar.showSnackbar(it.message ?: "Hiba") } } },
            onDismiss = { deleting = null }, destructive = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(existing: Category?, parentId: Long?, onDismiss: () -> Unit, onSave: (String, Long, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(existing?.colorArgb ?: CategoryVisuals.pickerColors.first()) }
    var icon by remember { mutableStateOf(existing?.iconKey ?: "other") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(if (existing == null) (if (parentId != null) "Új alkategória" else "Új kategória") else "Kategória szerkesztése", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Név") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Szín", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CategoryVisuals.pickerColors.forEach { col ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(CategoryVisuals.color(col))
                        .then(if (col == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .semantics { contentDescription = if (col == color) "Kiválasztott szín" else "Szín" }
                        .then(Modifier), contentAlignment = Alignment.Center) {
                        Surface(onClick = { color = col }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxSize()) {}
                    }
                }
            }
            Text("Ikon", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CategoryVisuals.iconOptions.forEach { (key, vector) ->
                    FilterChip(selected = icon == key, onClick = { icon = key }, label = { Icon(vector, contentDescription = key) })
                }
            }
            Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color, icon) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mentés") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val rules by c.learning.rulesFlow.collectAsState(initial = emptyList())
    val mappings by c.learning.mappingsFlow.collectAsState(initial = emptyList())
    val categories by c.categories.categories.collectAsState(initial = emptyList())
    val stores by c.stores.stores.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    val catById = categories.associateBy { it.id }
    val typeLabel = mapOf(RuleMatchType.CONTAINS to "tartalmazza", RuleMatchType.STARTS_WITH to "ezzel kezdődik", RuleMatchType.EXACT to "pontosan", RuleMatchType.REGEX to "reguláris kifejezés")

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kategorizálási szabályok") }, navigationIcon = { BackIcon(onBack) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Új szabály") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = 104.dp)) {
            item {
                Text("A szabályok a beépített szótár előtt érvényesülnek. A blokkokon javított kategóriákat a KiadásKövető automatikusan megjegyzi (lent).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md))
                SectionHeader("Saját szabályok")
            }
            if (rules.isEmpty()) item { Text("Még nincs saját szabály.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rules, key = { "r" + it.id }) { r ->
                val type = runCatching { RuleMatchType.valueOf(r.matchType) }.getOrNull()
                ListRow("„${r.pattern}” ${typeLabel[type] ?: ""}", subtitle = "→ ${catById[r.categoryId]?.name ?: "?"}" + (r.storeId?.let { id -> " · csak: ${stores.firstOrNull { it.id == id }?.name ?: "bolt"}" } ?: ""),
                    leading = { CategoryBadge(catById[r.categoryId], 32.dp) },
                    trailing = { IconButton(onClick = { scope.launch { c.learning.deleteRule(r.id) } }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Szabály törlése") } })
            }
            item { SectionHeader("Megjegyzett javítások (${mappings.size})") }
            if (mappings.isEmpty()) item { Text("Ha egy tétel kategóriáját módosítod, az itt jelenik meg.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(mappings, key = { "m" + it.id }) { m ->
                ListRow(m.matchKey, subtitle = "→ ${catById[m.categoryId]?.name ?: "?"} · " + (if (m.storeScope == 0L) "minden bolt" else stores.firstOrNull { it.id == m.storeScope }?.name ?: "bolt"),
                    leading = { CategoryBadge(catById[m.categoryId], 32.dp) },
                    trailing = { IconButton(onClick = { scope.launch { c.learning.deleteMapping(m.id) } }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Javítás elfelejtése") } })
            }
        }
    }
    if (adding) {
        var pattern by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(RuleMatchType.CONTAINS) }
        var categoryId by remember { mutableStateOf<Long?>(null) }
        var pick by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Új szabály", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(pattern, { pattern = it }, label = { Text("Minta (pl. KUTYA, BIO)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    RuleMatchType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(typeLabel[t]!!.substringBefore(' ')) }) }
                }
                ListRow(categories.firstOrNull { it.id == categoryId }?.name ?: "Kategória kiválasztása", leading = { CategoryBadge(categories.firstOrNull { it.id == categoryId }, 32.dp) }, onClick = { pick = true })
                Button(enabled = pattern.isNotBlank() && categoryId != null, onClick = {
                    scope.launch {
                        runCatching { c.learning.addRule(pattern, type, categoryId!!, null, 10) }.onSuccess { adding = false }.onFailure { snackbar.showSnackbar(it.message ?: "Hiba") }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mentés") }
            }
        }
        if (pick) CategoryPickerSheet(categories, categoryId, onPick = { categoryId = it; pick = false }, onDismiss = { pick = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val stores by c.stores.storesWithStats.collectAsState(initial = emptyList())
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<Long, String>?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Boltok") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        if (stores.isEmpty()) { EmptyState(Icons.Outlined.Storefront, "Nincs bolt", "A boltok a blokkok feldolgozásakor jönnek létre.", Modifier.padding(padding)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = Spacing.sm)) {
            items(stores, key = { it.id }) { s ->
                ListRow(s.name, subtitle = "${s.receiptCount} blokk · ${Fmt.money(s.total)}" + (s.address?.let { " · $it" } ?: ""),
                    leading = { Icon(Icons.Outlined.Storefront, null, tint = MaterialTheme.colorScheme.primary) },
                    trailing = { IconButton(onClick = { deleting = s.id to s.name }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Törlés") } },
                    onClick = { renaming = s.id to s.name })
            }
        }
    }
    renaming?.let { (id, old) ->
        var name by remember { mutableStateOf(old) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Bolt átnevezése") },
            text = { Column { Text("Ha egy már létező bolt nevét adod meg, a két bolt összevonásra kerül.", style = MaterialTheme.typography.bodySmall); OutlinedTextField(name, { name = it }, singleLine = true) } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { c.stores.rename(id, name) }; renaming = null }) { Text("Mentés") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Mégse") } })
    }
    deleting?.let { (id, name) ->
        ConfirmDialog("Bolt törlése", "„$name” törlődik. A blokkok megmaradnak, de bolt nélkül.", "Törlés",
            onConfirm = { scope.launch { c.stores.delete(id) } }, onDismiss = { deleting = null }, destructive = true)
    }
}

