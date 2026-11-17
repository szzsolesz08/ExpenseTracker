package hu.kiadaskoveto.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.data.repo.ItemInput
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.ReceiptItem
import hu.kiadaskoveto.domain.Store
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.DecimalField
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.MoneyField
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing

private fun Money.editText(): String = format(withCurrency = false).replace(" ", " ")

/** Bottom sheet to edit or add a receipt line. Printed values are shown, never silently recomputed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorSheet(
    item: ReceiptItem?,
    prefillName: String? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (ItemInput) -> Unit,
    onDelete: (() -> Unit)?,
    onConfirmAsIs: (() -> Unit)?,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(item?.displayName ?: prefillName.orEmpty()) }
    var kind by remember { mutableStateOf(item?.kind ?: ItemKind.PRODUCT) }
    var qty by remember { mutableStateOf(item?.quantity?.format().orEmpty()) }
    var unit by remember { mutableStateOf(item?.unit ?: MeasureUnit.PIECE) }
    var unitPrice by remember { mutableStateOf(item?.unitPrice?.editText().orEmpty()) }
    var lineTotal by remember { mutableStateOf(item?.lineTotal?.editText().orEmpty()) }
    var discount by remember { mutableStateOf(item?.discount?.takeIf { !it.isZero }?.let { (-it).editText() }.orEmpty()) }
    var categoryId by remember { mutableStateOf(item?.categoryId ?: categories.firstOrNull { it.isUncategorized }?.id) }
    var pickCategory by remember { mutableStateOf(false) }

    val q = AmountParser.parseQuantity(qty)
    val up = AmountParser.parseMoney(unitPrice)
    val total = AmountParser.parseMoney(lineTotal)
    val disc = AmountParser.parseMoney(discount)?.abs
    val computed = if (q != null && up != null) up * q else null
    val inconsistent = q != null && up != null && total != null && !PriceMath.isConsistent(q, up, total)
    val valid = name.isNotBlank() && total != null && (discount.isBlank() || disc != null)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(if (item == null) "Új tétel" else "Tétel szerkesztése", style = MaterialTheme.typography.titleLarge)
            if (item?.reviewNote != null) Notice(item.reviewNote)
            if (!item?.originalText.isNullOrBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text("A blokkon:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item!!.originalText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ItemKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Megnevezés") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (kind == ItemKind.PRODUCT) {
                val cat = categories.firstOrNull { it.id == categoryId }
                Surface(onClick = { pickCategory = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(cat, 32.dp)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text("Kategória", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(cat?.name ?: "Nem besorolt", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text("Módosítás", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    DecimalField(qty, { qty = it }, "Mennyiség", Modifier.weight(1f))
                    listOf(MeasureUnit.PIECE, MeasureUnit.KILOGRAM, MeasureUnit.LITER).forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.symbol) })
                    }
                }
                MoneyField(unitPrice, { unitPrice = it }, if (unit == MeasureUnit.PIECE) "Egységár" else "Ár / ${unit.symbol}", Modifier.fillMaxWidth(),
                    supporting = "Csak ha a blokkon szerepel")
            }
            MoneyField(lineTotal, { lineTotal = it }, "Sorár (a blokkon)", Modifier.fillMaxWidth(), allowNegative = kind != ItemKind.PRODUCT)
            if (computed != null && total == null) {
                TextButton(onClick = { lineTotal = computed.editText() }) { Text("Sorár számítása: ${q!!.format()} × ${Fmt.money(up!!)} = ${Fmt.money(computed)}") }
            }
            if (inconsistent) Notice("A mennyiség × egységár (${Fmt.money(computed!!)}) nem egyezik a sorárral. A blokkon szereplő sorár marad érvényes.")
            if (kind == ItemKind.PRODUCT) MoneyField(discount, { discount = it }, "Kedvezmény (levonás)", Modifier.fillMaxWidth())

            Spacer(Modifier.height(Spacing.sm))
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        ItemInput(
                            kind = kind,
                            rawName = item?.rawName ?: name,
                            displayName = name,
                            categoryId = if (kind == ItemKind.PRODUCT) categoryId else categories.firstOrNull { it.key == "other" }?.id,
                            quantity = q,
                            unit = if (q != null || kind == ItemKind.PRODUCT && unit != MeasureUnit.PIECE) unit else null,
                            unitPrice = up,
                            lineTotal = total!!.let { if (kind == ItemKind.DISCOUNT || kind == ItemKind.DEPOSIT_RETURN) -it.abs else it },
                            discount = disc?.let { -it } ?: Money.ZERO,
                            discountText = item?.discountText,
                            vatCode = item?.vatCode,
                            originalText = item?.originalText.orEmpty(),
                            productId = item?.productId,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Mentés") }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (onConfirmAsIs != null && item?.needsReview == true) OutlinedButton(onClick = onConfirmAsIs, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(Spacing.xs)); Text("Rendben van")
                }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Tétel törlése", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (pickCategory) CategoryPickerSheet(categories, categoryId, onPick = { categoryId = it; pickCategory = false }, onDismiss = { pickCategory = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(categories: List<Category>, selectedId: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.lg)) {
            Text("Kategória", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
            Text("A választást a KiadásKövető megjegyzi a következő blokkokhoz.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xl))
            val roots = categories.filter { it.parentId == null }
            roots.forEach { root ->
                CategoryOption(root, root.id == selectedId, indent = false) { onPick(root.id) }
                categories.filter { it.parentId == root.id }.forEach { child -> CategoryOption(child, child.id == selectedId, indent = true) { onPick(child.id) } }
            }
        }
    }
}

@Composable
private fun CategoryOption(c: Category, selected: Boolean, indent: Boolean, onClick: () -> Unit) {
    ListRow(
        title = c.name,
        modifier = Modifier.padding(start = if (indent) Spacing.xxl else 0.dp),
        leading = { CategoryBadge(c, 36.dp) },
        trailing = { if (selected) Icon(Icons.Outlined.Check, contentDescription = "Kiválasztva", tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePickerSheet(stores: List<Store>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(current.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = Spacing.lg)) {
            Text("Bolt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
            OutlinedTextField(query, { query = it }, label = { Text("Bolt neve") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl))
            if (query.isNotBlank() && stores.none { it.name.equals(query.trim(), ignoreCase = true) }) {
                ListRow("„${query.trim()}” használata", subtitle = "Új bolt létrehozása", leading = { Icon(Icons.Outlined.Add, null) }, onClick = { onPick(query.trim()) })
            }
            val key = hu.kiadaskoveto.core.text.HungarianText.matchKey(query)
            stores.filter { key.isBlank() || hu.kiadaskoveto.core.text.HungarianText.matchKey(it.name).contains(key) }.forEach { s ->
                ListRow(s.name, subtitle = s.address, onClick = { onPick(s.name) })
            }
        }
    }
}
