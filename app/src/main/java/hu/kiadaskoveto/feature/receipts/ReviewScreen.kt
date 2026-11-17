package hu.kiadaskoveto.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.data.repo.DuplicateInfo
import hu.kiadaskoveto.data.repo.ItemInput
import hu.kiadaskoveto.data.repo.ReceiptDetail
import hu.kiadaskoveto.data.work.WorkScheduler
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.ReceiptItem
import hu.kiadaskoveto.domain.ReceiptStatus
import hu.kiadaskoveto.domain.Store
import hu.kiadaskoveto.domain.paymentLabel
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.DateField
import hu.kiadaskoveto.ui.components.LoadingState
import hu.kiadaskoveto.ui.components.MoneyField
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.components.ReviewMarker
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class ReviewViewModel(private val c: AppContainer, private val receiptId: Long) : ViewModel() {
    val detail: StateFlow<ReceiptDetail?> = c.receipts.observeDetail(receiptId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val stores: StateFlow<List<Store>> = c.stores.stores.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val duplicate = MutableStateFlow<DuplicateInfo?>(null)
    val error = MutableStateFlow<String?>(null)

    init { refreshDuplicate() }

    fun refreshDuplicate() = viewModelScope.launch { duplicate.value = runCatching { c.receipts.findDuplicate(receiptId) }.getOrNull() }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { error.value = e.message ?: "Hiba történt." }
    }

    fun updateHeader(store: String?, date: LocalDate, time: LocalTime?, total: Money?, payment: String?, notes: String?) = act {
        c.receipts.updateHeader(receiptId, store, date.atTime(time ?: LocalTime.NOON), time != null, total, payment, notes)
        refreshDuplicate()
    }
    fun saveItem(itemId: Long?, input: ItemInput) = act { if (itemId == null) c.receipts.addItem(receiptId, input) else c.receipts.updateItem(itemId, input); refreshDuplicate() }
    fun deleteItem(itemId: Long) = act { c.receipts.deleteItem(itemId) }
    fun setCategory(itemId: Long, categoryId: Long) = act { c.receipts.setItemCategory(itemId, categoryId) }
    fun markReviewed(itemId: Long) = act { c.receipts.markItemReviewed(itemId) }
    fun overrideDuplicate() = act { c.receipts.overrideDuplicate(receiptId); duplicate.value = null }
    fun delete(onDone: () -> Unit) = act { c.receipts.delete(receiptId); onDone() }
    fun confirm(onDone: (Long) -> Unit) = act {
        c.receipts.confirm(receiptId)
        WorkScheduler.checkBudgetsNow(c.context)
        onDone(receiptId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    receiptId: Long,
    fresh: Boolean,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenDocument: () -> Unit,
    onOpenDuplicate: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: ReviewViewModel = viewModel(key = "review$receiptId") { ReviewViewModel(c, receiptId) }
    val detail by vm.detail.collectAsState()
    val categories by vm.categories.collectAsState()
    val stores by vm.stores.collectAsState()
    val duplicate by vm.duplicate.collectAsState()
    val error by vm.error.collectAsState()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.error.value = null } }

    var editing by remember { mutableStateOf<ReceiptItem?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var pickCategoryFor by remember { mutableStateOf<ReceiptItem?>(null) }
    var pickStore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSaveWithOpenItems by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    val d = detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (d?.receipt?.status == ReceiptStatus.CONFIRMED) "Blokk szerkesztése" else "Blokk ellenőrzése") },
                navigationIcon = { BackIcon(onBack) },
                actions = {
                    if (d?.documents?.isNotEmpty() == true) IconButton(onClick = onOpenDocument) { Icon(Icons.Outlined.Image, contentDescription = "Eredeti blokk megtekintése") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Blokk törlése") }
                },
            )
        },
        bottomBar = {
            if (d != null) {
                val open = d.items.count { it.needsReview }
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.gutter, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Végösszeg", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(d.receipt.total, style = MaterialTheme.typography.titleLarge)
                        }
                        Button(
                            onClick = {
                                when {
                                    !d.receipt.dateKnown -> scope.launch { snackbar.showSnackbar("Add meg a vásárlás dátumát a mentés előtt.") }
                                    d.items.isEmpty() -> scope.launch { snackbar.showSnackbar("Adj hozzá legalább egy tételt.") }
                                    open > 0 -> confirmSaveWithOpenItems = true
                                    else -> vm.confirm(onSaved)
                                }
                            },
                            modifier = Modifier.heightIn(min = 52.dp),
                        ) { Text(if (open > 0) "Mentés ($open ellenőrizetlen)" else "Mentés") }
                    }
                }
            }
        },
    ) { padding ->
        if (d == null) { LoadingState(modifier = Modifier.padding(padding)); return@Scaffold }
        val r = d.receipt
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md)) {
            if (r.status == ReceiptStatus.FAILED && r.failureReason != null) item { Notice(r.failureReason, isError = true); Spacer(Modifier.height(Spacing.sm)) }
            if (fresh && r.confidence < 0.6 && d.items.isNotEmpty()) item {
                Notice("A felismerés bizonytalan volt. Nézd át a tételeket és az összegeket az eredeti képpel összevetve.", action = "Kép", onAction = onOpenDocument)
                Spacer(Modifier.height(Spacing.sm))
            }
            duplicate?.let { dup ->
                item {
                    Notice(
                        "Lehetséges duplikátum: ${dup.reasonText}" + (dup.other?.let { " (${dup.otherStoreName ?: "ismeretlen bolt"}, ${Fmt.money(it.totalMinor)})" } ?: ""),
                        icon = Icons.Outlined.ContentCopy,
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        dup.match.receiptId?.let { id -> TextButton(onClick = { onOpenDuplicate(id) }) { Text("Korábbi megnyitása") } }
                        TextButton(onClick = { vm.overrideDuplicate() }) { Text("Nem duplikátum, mentés így is") }
                    }
                }
            }

            item { HeaderCard(d, onPickStore = { pickStore = true }, onUpdate = vm::updateHeader) }

            item {
                SectionHeader("Tételek (${d.items.size})", action = "Új tétel", onAction = { adding = "" })
            }
            if (d.items.isEmpty()) item {
                Text("Nincsenek tételek. Add hozzá őket kézzel, vagy vedd fel a lent látható, fel nem dolgozott sorokat.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm))
            }
            items(d.items, key = { it.id }) { item ->
                ItemRow(item, categories.firstOrNull { it.id == item.categoryId }, onClick = { editing = item }, onCategory = { pickCategoryFor = item })
            }

            r.unparsedText?.takeIf { it.isNotBlank() }?.let { text ->
                item {
                    SectionHeader("Fel nem dolgozott sorok")
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.sm)) {
                        Text("Ezeket a sorokat nem tudtuk tételhez rendelni. Ha valamelyik vásárolt termék, vedd fel.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.lg))
                        text.lines().filter { it.isNotBlank() }.forEach { line ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                                Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { adding = line }) { Text("Felvétel") }
                            }
                        }
                    }
                }
            }

            if (!r.ocrText.isNullOrBlank()) {
                item {
                    TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.padding(top = Spacing.md)) { Text(if (showRaw) "Felismert szöveg elrejtése" else "Felismert szöveg megjelenítése") }
                    if (showRaw) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                        Text(r.ocrText, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(Spacing.md))
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.section)) }
        }
    }

    editing?.let { item ->
        ItemEditorSheet(
            item = item, categories = categories, onDismiss = { editing = null },
            onSave = { vm.saveItem(item.id, it); editing = null },
            onDelete = { vm.deleteItem(item.id); editing = null },
            onConfirmAsIs = { vm.markReviewed(item.id); editing = null },
        )
    }
    adding?.let { prefill ->
        ItemEditorSheet(item = null, prefillName = prefill, categories = categories, onDismiss = { adding = null },
            onSave = { vm.saveItem(null, it); adding = null }, onDelete = null, onConfirmAsIs = null)
    }
    pickCategoryFor?.let { item ->
        CategoryPickerSheet(categories, item.categoryId, onPick = { vm.setCategory(item.id, it); pickCategoryFor = null }, onDismiss = { pickCategoryFor = null })
    }
    if (pickStore && d != null) {
        StorePickerSheet(stores, d.receipt.storeName, onPick = { name ->
            pickStore = false
            val r = d.receipt
            vm.updateHeader(name, r.purchasedAt.toLocalDate(), if (r.hasTime) r.purchasedAt.toLocalTime() else null, r.printedTotal, r.paymentMethod, r.notes)
        }, onDismiss = { pickStore = false })
    }
    if (confirmDelete) ConfirmDialog("Blokk törlése", "A blokk, a tételei és az eredeti képek véglegesen törlődnek a telefonról.", "Törlés",
        onConfirm = { vm.delete(onDeleted) }, onDismiss = { confirmDelete = false }, destructive = true)
    if (confirmSaveWithOpenItems && d != null) ConfirmDialog("Ellenőrizetlen sorok",
        "${d.items.count { it.needsReview }} sor még ellenőrzésre vár. Mented így? A sorok jelölése megmarad, később is javíthatod őket.",
        "Mentés így", onConfirm = { vm.confirm(onSaved) }, onDismiss = { confirmSaveWithOpenItems = false })
}

@Composable
private fun HeaderCard(d: ReceiptDetail, onPickStore: () -> Unit, onUpdate: (String?, LocalDate, LocalTime?, Money?, String?, String?) -> Unit) {
    val r = d.receipt
    var totalText by remember(r.printedTotal) { mutableStateOf(r.printedTotal?.format(withCurrency = false)?.replace(" ", " ").orEmpty()) }
    val time = if (r.hasTime) r.purchasedAt.toLocalTime() else null
    fun save(store: String? = r.storeName, date: LocalDate = r.purchasedAt.toLocalDate(), total: Money? = r.printedTotal, payment: String? = r.paymentMethod) =
        onUpdate(store, date, time, total, payment, r.notes)

    AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Surface(onClick = onPickStore, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text("Bolt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.storeName ?: "Ismeretlen bolt — válassz", style = MaterialTheme.typography.bodyLarge)
                    r.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (r.storeId == null) ReviewMarker("Hiányzik")
            }
        }
        Spacer(Modifier.height(Spacing.md))
        DateField(if (r.dateKnown) r.purchasedAt.toLocalDate() else null, { save(date = it) },
            label = if (r.hasTime) "Vásárlás dátuma (${r.purchasedAt.toLocalTime()})" else "Vásárlás dátuma",
            modifier = Modifier.fillMaxWidth(), isError = !r.dateKnown)
        if (!r.dateKnown) Text("A blokkon nem találtunk dátumot — add meg, mielőtt mented.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(Spacing.md))
        MoneyField(totalText, { totalText = it }, "Végösszeg a blokkon", Modifier.fillMaxWidth(),
            supporting = if (r.printedTotal == null) "Nem olvasható — a tételek összege számít" else null)
        val parsedTotal = AmountParser.parseMoney(totalText)
        if (parsedTotal != r.printedTotal && (totalText.isBlank() || parsedTotal != null)) {
            TextButton(onClick = { save(total = parsedTotal) }) { Text("Végösszeg mentése") }
        }
        Reconciliation(r.computedTotal, r.printedTotal ?: r.payableTotal, r.rounding)
        Spacer(Modifier.height(Spacing.sm))
        Text("Fizetés módja", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf("CARD", "CASH", "SZEP_CARD", "VOUCHER").forEach { m ->
                FilterChip(selected = r.paymentMethod == m, onClick = { save(payment = if (r.paymentMethod == m) null else m) }, label = { Text(paymentLabel(m)!!) })
            }
        }
        if (r.receiptNumber != null || r.apCode != null) {
            Text(listOfNotNull(r.receiptNumber?.let { "Nyugtaszám: $it" }, r.apCode?.let { "AP: $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
}

@Composable
private fun Reconciliation(sum: Money, printed: Money?, rounding: Money?) {
    val ext = AppTheme.extended
    val diff = printed?.let { it - sum }
    val ok = diff != null && diff.isZero
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem, contentDescription = null,
            tint = if (ok) ext.positive else ext.warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            when {
                printed == null -> "Tételek összege: ${Fmt.money(sum)} (nincs ellenőrizhető végösszeg)"
                ok -> "A tételek összege egyezik a végösszeggel" + (rounding?.takeIf { !it.isZero }?.let { " · kerekítés ${Fmt.money(it)}" } ?: "")
                else -> "Tételek összege ${Fmt.money(sum)} · eltérés ${Fmt.money(diff!!)}"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ItemRow(item: ReceiptItem, category: Category?, onClick: () -> Unit, onCategory: (() -> Unit)?) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            if (item.kind == ItemKind.PRODUCT && onCategory != null) {
                IconButton(onClick = onCategory, modifier = Modifier.size(48.dp)) { CategoryBadge(category, 36.dp) }
            } else CategoryBadge(category, 36.dp, Modifier.padding(6.dp))
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.rawName.isNotBlank() && item.rawName != item.displayName) {
                    Text(item.rawName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val details = buildList {
                    if (item.quantity != null) add("${item.quantity.format()} ${item.unit?.symbol ?: "db"}" + (item.unitPrice?.let { " × ${Fmt.money(it)}" } ?: ""))
                    if (item.kind != ItemKind.PRODUCT) add(item.kind.label)
                    category?.let { if (item.kind == ItemKind.PRODUCT) add(it.name) }
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!item.discount.isZero) Text("Kedvezmény ${Fmt.money(item.discount)}", style = MaterialTheme.typography.bodySmall.tabular, color = AppTheme.extended.positive)
                if (item.needsReview) ReviewMarker(item.reviewNote?.substringBefore(";") ?: "Ellenőrizd", Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(item.finalPrice, style = MaterialTheme.typography.bodyLarge)
                if (!item.discount.isZero) Text(Fmt.money(item.lineTotal), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

