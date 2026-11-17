package hu.kiadaskoveto.feature.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.repo.PriceNormalizer
import hu.kiadaskoveto.data.repo.packageSizeOf
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.feature.receipts.CategoryPickerSheet
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.DecimalField
import hu.kiadaskoveto.ui.components.LineChart
import hu.kiadaskoveto.ui.components.LinePoint
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.LoadingState
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(productId: Long, onBack: () -> Unit, onOpenReceipt: (Long) -> Unit, onOpenProduct: (Long) -> Unit, onScanBarcode: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val product by c.products.observe(productId).collectAsState(initial = null)
    val aliases by c.products.observeAliases(productId).collectAsState(initial = emptyList())
    val purchases by c.products.observePurchases(productId).collectAsState(initial = emptyList())
    val history by c.products.observePriceHistory(productId).collectAsState(initial = emptyList())
    val categories by c.categories.categories.collectAsState(initial = emptyList())
    val stores by c.stores.stores.collectAsState(initial = emptyList())
    val all by c.products.productsWithStats.collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) = scope.launch { try { block() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Hiba történt.") } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(product?.name ?: "Termék", maxLines = 1) }, navigationIcon = { BackIcon(onBack) }, actions = {
            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, contentDescription = "Szerkesztés") }
            IconButton(onClick = onScanBarcode) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Vonalkód hozzárendelése") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Továbbiak") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Összevonás másik termékkel") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, null) }, onClick = { menu = false; merging = true })
                DropdownMenuItem(text = { Text("Termék törlése") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; confirmDelete = true })
            }
        })
    }) { padding ->
        val p = product
        if (p == null) { LoadingState(modifier = Modifier.padding(padding)); return@Scaffold }
        val category = categories.firstOrNull { it.id == p.categoryId }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(category, 56.dp)
                    Spacer(Modifier.width(Spacing.lg))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall)
                        val meta = listOfNotNull(p.brand, p.productType, p.variant, packageSizeOf(p.packageSizeAmount, p.packageSizeUnit)?.format(), category?.name)
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        p.barcode?.let { Text("Vonalkód: $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                val total = purchases.sumOf { it.lineTotalMinor + it.discountMinor }
                AppCard(Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        Column(Modifier.weight(1f)) { Text("Összesen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(Money(total), style = MaterialTheme.typography.titleMedium) }
                        Column(Modifier.weight(1f)) { Text("Vásárlások", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${purchases.size}×", style = MaterialTheme.typography.titleMedium.tabular) }
                        Column(Modifier.weight(1f)) {
                            Text("Átlagár", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(PriceMath.average(Money(total), purchases.size).roundedToForint(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val first = p.firstPurchasedAt
                    if (first != null) Text(
                        "Első vásárlás: ${Fmt.date(WallClock.fromStored(first).toLocalDate())} · utolsó: ${Fmt.date(WallClock.fromStored(p.lastPurchasedAt ?: first).toLocalDate())}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                }
            }
            // Price development on one comparable basis
            val comparable = history.mapNotNull { PriceNormalizer.comparable(it) }
            val groups = comparable.groupBy { it.basis.key }
            val main = groups.maxByOrNull { it.value.size }?.value.orEmpty()
            if (main.size >= 2) item {
                SectionHeader("Árak alakulása (${main.first().basis.label})")
                AppCard(Modifier.fillMaxWidth()) {
                    LineChart(
                        main.map { LinePoint(Fmt.dateShort(it.date.toLocalDate()), it.price.minor, "${Fmt.date(it.date.toLocalDate())} · ${it.point.storeName ?: ""}") },
                        summary = "Árak: " + main.joinToString("; ") { "${Fmt.date(it.date.toLocalDate())} ${Fmt.money(it.price)}" },
                    )
                    val prev = main[main.size - 2]; val cur = main.last()
                    val diff = cur.price - prev.price
                    val pct = PriceMath.percentChange(prev.price, cur.price)
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        Column(Modifier.weight(1f)) { Text("Előző ár", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(prev.price) }
                        Column(Modifier.weight(1f)) { Text("Jelenlegi ár", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(cur.price) }
                        Column(Modifier.weight(1f)) {
                            Text("Változás", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text((if (diff.isPositive) "+" else "") + Fmt.money(diff) + (pct?.let { " (${Fmt.signedPercent(it)})" } ?: ""),
                                style = MaterialTheme.typography.bodyLarge.tabular,
                                color = if (diff.isPositive) MaterialTheme.colorScheme.error else if (diff.isNegative) AppTheme.extended.positive else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (groups.size > 1) Text("Más kiszerelésben is vásároltad; azokat nem vetjük össze közvetlenül.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                }
            } else item {
                Text("Az ártörténet két összevethető vásárlás után jelenik meg.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.lg))
            }
            if (purchases.isNotEmpty()) item {
                SectionHeader("Vásárlások")
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    purchases.forEach { pu ->
                        val qty = pu.quantityMilli?.let { hu.kiadaskoveto.core.money.Quantity(it).format() + " " + (pu.unit?.let { u -> runCatching { MeasureUnit.valueOf(u).symbol }.getOrNull() } ?: "db") }
                        ListRow(
                            "${Fmt.date(WallClock.fromStored(pu.purchasedAt).toLocalDate())} · ${pu.storeName ?: "Ismeretlen bolt"}",
                            subtitle = listOfNotNull(pu.rawName, qty).joinToString(" · "),
                            trailing = { MoneyText(Money(pu.lineTotalMinor + pu.discountMinor), style = MaterialTheme.typography.bodyMedium) },
                            onClick = { onOpenReceipt(pu.receiptId) },
                        )
                    }
                }
            }
            item {
                SectionHeader("Blokkon szereplő nevek")
                Text("Ezek a leírások kapcsolódnak automatikusan ehhez a termékhez a megadott boltban. Ha egy leírás más terméket jelöl, válaszd le.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    if (aliases.isEmpty()) Text("Nincs rögzített név.", modifier = Modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    aliases.forEach { a ->
                        val scope2 = if (a.storeScope == 0L) "minden bolt" else stores.firstOrNull { it.id == a.storeScope }?.name ?: "bolt #${a.storeScope}"
                        ListRow(a.originalText, subtitle = scope2, trailing = {
                            Row {
                                if (aliases.size > 1) IconButton(onClick = { run { val newId = c.products.splitAlias(a.id, a.originalText, productId); onOpenProduct(newId) } }) {
                                    Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = "Leválasztás külön termékké")
                                }
                                IconButton(onClick = { run { c.products.deleteAlias(a.id) } }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Név eltávolítása") }
                            }
                        })
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.section)) }
        }
        if (editing) ProductEditSheet(p, categories, onDismiss = { editing = false }, onSave = { run { c.products.update(it); editing = false } })
    }

    if (merging) {
        ModalBottomSheet(onDismissRequest = { merging = false }) {
            var q by remember { mutableStateOf("") }
            var target by remember { mutableStateOf<ProductEntity?>(null) }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = Spacing.lg)) {
                Text("Összevonás", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl))
                Text("Válaszd ki, melyik termékbe kerüljön ez a termék. A vásárlások és a blokknevek átkerülnek, ez a termék megszűnik.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
                OutlinedTextField(q, { q = it }, label = { Text("Keresés") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl))
                val key = HungarianText.matchKey(q)
                all.filter { it.product.id != productId && (key.isBlank() || HungarianText.matchKey(it.product.name).contains(key)) }.take(30).forEach { o ->
                    ListRow(o.product.name, subtitle = "${o.purchaseCount}× vásárolva", onClick = { target = o.product })
                }
            }
            target?.let { t ->
                ConfirmDialog("Összevonás", "„${product?.name}” összevonása ezzel: „${t.name}”. A vásárlások és nevek átkerülnek.", "Összevonás",
                    onConfirm = { run { c.products.merge(productId, t.id); merging = false; onOpenProduct(t.id) } }, onDismiss = { target = null })
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Termék törlése", "A termék és a hozzá rendelt blokknevek törlődnek. A blokkok tételei megmaradnak, csak a termékkapcsolat szűnik meg.", "Törlés",
        onConfirm = { run { c.products.delete(productId); onBack() } }, onDismiss = { confirmDelete = false }, destructive = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductEditSheet(p: ProductEntity, categories: List<hu.kiadaskoveto.domain.Category>, onDismiss: () -> Unit, onSave: (ProductEntity) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var brand by remember { mutableStateOf(p.brand.orEmpty()) }
    var type by remember { mutableStateOf(p.productType.orEmpty()) }
    var variant by remember { mutableStateOf(p.variant.orEmpty()) }
    var size by remember { mutableStateOf(p.packageSizeAmount.orEmpty().replace('.', ',')) }
    var sizeUnit by remember { mutableStateOf(p.packageSizeUnit?.let { runCatching { MeasureUnit.valueOf(it) }.getOrNull() } ?: MeasureUnit.PIECE) }
    var barcode by remember { mutableStateOf(p.barcode.orEmpty()) }
    var categoryId by remember { mutableStateOf(p.categoryId) }
    var pickCategory by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Termék szerkesztése", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Név") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(brand, { brand = it }, label = { Text("Márka") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(type, { type = it }, label = { Text("Terméktípus") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(variant, { variant = it }, label = { Text("Változat (pl. 2,8%, laktózmentes)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DecimalField(size, { size = it }, "Kiszerelés", Modifier.weight(1f))
                listOf(MeasureUnit.GRAM, MeasureUnit.KILOGRAM, MeasureUnit.MILLILITER, MeasureUnit.LITER, MeasureUnit.PIECE).forEach { u ->
                    FilterChip(selected = sizeUnit == u, onClick = { sizeUnit = u }, label = { Text(u.symbol) })
                }
            }
            Surface(onClick = { pickCategory = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    val cat = categories.firstOrNull { it.id == categoryId }
                    CategoryBadge(cat, 32.dp); Spacer(Modifier.width(Spacing.md))
                    Text(cat?.name ?: "Nem besorolt", modifier = Modifier.weight(1f))
                    Text("Módosítás", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedTextField(barcode, { barcode = it.filter { ch -> ch.isLetterOrDigit() } }, label = { Text("Vonalkód (EAN)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = name.isNotBlank(), onClick = {
                val sizeValue = size.replace(',', '.').toBigDecimalOrNull()
                onSave(p.copy(name = name.trim(), brand = brand.trim().ifBlank { null }, productType = type.trim().ifBlank { null }, variant = variant.trim().ifBlank { null },
                    packageSizeAmount = sizeValue?.stripTrailingZeros()?.toPlainString(), packageSizeUnit = if (sizeValue != null) sizeUnit.name else null,
                    barcode = barcode.ifBlank { null }, categoryId = categoryId))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mentés") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Mégse") }
        }
    }
    if (pickCategory) CategoryPickerSheet(categories, categoryId, onPick = { categoryId = it; pickCategory = false }, onDismiss = { pickCategory = false })
}
