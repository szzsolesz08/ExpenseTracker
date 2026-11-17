package hu.kiadaskoveto.feature.products

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing

private enum class ProductSort(val label: String) { NAME("Név"), FREQUENCY("Gyakoriság"), SPEND("Költés"), RECENT("Legutóbbi") }

@Composable
fun ProductsScreen(onOpenProduct: (Long) -> Unit, onScanBarcode: () -> Unit) {
    val c = LocalAppContainer.current
    val products by c.products.productsWithStats.collectAsState(initial = null)
    val categories by c.categories.categories.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ProductSort.FREQUENCY) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val byId = categories.associateBy { it.id }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.sm, top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text("Termékek", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onScanBarcode) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Vonalkód beolvasása") }
        }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Keresés terméknévre") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, contentDescription = "Törlés") } },
            singleLine = true, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ProductSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("Minden kategória") })
            categories.filter { cat -> products?.any { it.product.categoryId == cat.id } == true }.forEach { cat ->
                FilterChip(selected = categoryId == cat.id, onClick = { categoryId = if (categoryId == cat.id) null else cat.id }, label = { Text(cat.name) },
                    leadingIcon = { CategoryBadge(cat, 20.dp) })
            }
        }
        val list = products ?: return@Column
        val key = HungarianText.matchKey(query)
        val filtered = list.filter { (key.isBlank() || HungarianText.matchKey(it.product.name).contains(key) || (it.product.brand?.let { b -> HungarianText.matchKey(b).contains(key) } == true)) &&
            (categoryId == null || it.product.categoryId == categoryId) }
            .let { l ->
                when (sort) {
                    ProductSort.NAME -> l.sortedBy { it.product.name.lowercase(Fmt.HU) }
                    ProductSort.FREQUENCY -> l.sortedByDescending { it.purchaseCount }
                    ProductSort.SPEND -> l.sortedByDescending { it.totalSpent }
                    ProductSort.RECENT -> l.sortedByDescending { it.product.lastPurchasedAt ?: 0 }
                }
            }
        if (list.isEmpty()) {
            EmptyState(Icons.Outlined.Inventory2, "Még nincsenek termékek",
                "A termékek a blokkok mentésekor jönnek létre. Itt követheted az áraikat és azt, hogy milyen gyakran vásárolod őket.")
            return@Column
        }
        if (filtered.isEmpty()) { EmptyState(Icons.Outlined.Search, "Nincs találat", "Próbálj másik keresőszót vagy kategóriát."); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.section)) {
            items(filtered, key = { it.product.id }) { p ->
                val sub = buildList {
                    if (p.purchaseCount > 0) add("${p.purchaseCount}× vásárolva")
                    p.product.lastPurchasedAt?.let { add("utoljára ${Fmt.dateShort(WallClock.fromStored(it).toLocalDate())}") }
                    p.product.brand?.let { add(it) }
                }.joinToString(" · ")
                ListRow(p.product.name, subtitle = sub.ifBlank { null }, leading = { CategoryBadge(byId[p.product.categoryId], 40.dp) },
                    trailing = { MoneyText(Money(p.totalSpent), style = MaterialTheme.typography.bodyMedium) }, onClick = { onOpenProduct(p.product.id) })
            }
        }
    }
}
