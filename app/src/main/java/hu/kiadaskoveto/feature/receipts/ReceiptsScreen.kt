package hu.kiadaskoveto.feature.receipts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.repo.ReceiptFilter
import hu.kiadaskoveto.database.model.ReceiptSummary
import hu.kiadaskoveto.domain.Store
import hu.kiadaskoveto.feature.common.ReceiptRow
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.DateField
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.MoneyField
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth

enum class DatePreset(val label: String) { ALL("Bármikor"), THIS_MONTH("Ez a hónap"), LAST_MONTH("Előző hónap"), LAST_3("Utolsó 3 hónap"), THIS_YEAR("Idei év"), CUSTOM("Egyéni időszak") }

data class ReceiptsFilterState(
    val query: String = "",
    val store: Store? = null,
    val preset: DatePreset = DatePreset.ALL,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val minText: String = "",
    val maxText: String = "",
) {
    fun toFilter(): ReceiptFilter {
        val now = YearMonth.now()
        val period = when (preset) {
            DatePreset.ALL -> null
            DatePreset.THIS_MONTH -> Period.month(now)
            DatePreset.LAST_MONTH -> Period.month(now.minusMonths(1))
            DatePreset.LAST_3 -> Period.months(now.minusMonths(2), now)
            DatePreset.THIS_YEAR -> Period.months(YearMonth.of(now.year, 1), YearMonth.of(now.year, 12))
            DatePreset.CUSTOM -> if (customFrom != null && customTo != null) Period.days(minOf(customFrom, customTo), maxOf(customFrom, customTo)) else null
        }
        return ReceiptFilter(
            query = query.trim(),
            storeId = store?.id,
            from = period?.from ?: Long.MIN_VALUE / 4,
            to = period?.to ?: Long.MAX_VALUE / 4,
            minTotal = AmountParser.parseMoney(minText)?.minor ?: Long.MIN_VALUE / 4,
            maxTotal = AmountParser.parseMoney(maxText)?.minor ?: Long.MAX_VALUE / 4,
            includeDrafts = true,
        )
    }
    val isFiltered: Boolean get() = query.isNotBlank() || store != null || preset != DatePreset.ALL || minText.isNotBlank() || maxText.isNotBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptsViewModel(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(ReceiptsFilterState())
    val stores: StateFlow<List<Store>> = c.stores.stores.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<ReceiptSummary>?> = filter.flatMapLatest { c.receipts.search(it.toFilter()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun update(f: (ReceiptsFilterState) -> ReceiptsFilterState) = filter.update(f)
}

private enum class Sheet { NONE, STORE, DATE, AMOUNT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReceiptsScreen(onOpenReceipt: (Long, Boolean) -> Unit, onAddReceipt: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: ReceiptsViewModel = viewModel { ReceiptsViewModel(c) }
    val f by vm.filter.collectAsState()
    val list by vm.receipts.collectAsState()
    val stores by vm.stores.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Text("Blokkok", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg))
        OutlinedTextField(
            value = f.query,
            onValueChange = { q -> vm.update { it.copy(query = q) } },
            placeholder = { Text("Keresés boltra vagy termékre") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = { if (f.query.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Outlined.Clear, contentDescription = "Keresés törlése") } },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = f.store != null, onClick = { sheet = Sheet.STORE }, label = { Text(f.store?.name ?: "Bolt") })
            FilterChip(selected = f.preset != DatePreset.ALL, onClick = { sheet = Sheet.DATE }, label = { Text(if (f.preset == DatePreset.ALL) "Időszak" else f.preset.label) })
            FilterChip(selected = f.minText.isNotBlank() || f.maxText.isNotBlank(), onClick = { sheet = Sheet.AMOUNT }, label = {
                Text(when {
                    f.minText.isNotBlank() && f.maxText.isNotBlank() -> "${f.minText}–${f.maxText} Ft"
                    f.minText.isNotBlank() -> "legalább ${f.minText} Ft"
                    f.maxText.isNotBlank() -> "legfeljebb ${f.maxText} Ft"
                    else -> "Összeg"
                })
            })
            if (f.isFiltered) TextButton(onClick = { vm.update { ReceiptsFilterState() } }) { Text("Szűrők törlése") }
        }

        val items = list
        when {
            items == null -> Unit
            items.isEmpty() && !f.isFiltered -> EmptyState(
                Icons.AutoMirrored.Outlined.ReceiptLong, "Még nincs blokk",
                "A hozzáadott blokkok itt jelennek meg, havonta csoportosítva. A felismerés után minden tételt átnézhetsz mentés előtt.",
                primaryAction = "Blokk hozzáadása", onPrimary = onAddReceipt,
            )
            items.isEmpty() -> EmptyState(Icons.Outlined.SearchOff, "Nincs találat", "Próbálj más keresőszót vagy lazább szűrést.",
                primaryAction = "Szűrők törlése", onPrimary = { vm.update { ReceiptsFilterState() } })
            else -> {
                val pending = items.filter { it.status != "CONFIRMED" }
                val confirmed = items.filter { it.status == "CONFIRMED" }
                val byMonth = confirmed.groupBy { YearMonth.from(WallClock.fromStored(it.purchasedAt)) }
                LazyColumn(contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = 104.dp)) {
                    if (pending.isNotEmpty()) {
                        stickyHeader { GroupHeader("Jóváhagyásra vár", null) }
                        items(pending, key = { "p" + it.id }) { r -> ReceiptRow(r, onClick = { onOpenReceipt(r.id, true) }) }
                    }
                    byMonth.forEach { (ym, rs) ->
                        stickyHeader(key = "h$ym") { GroupHeader(Fmt.monthTitle(ym), rs.sumOf { it.totalMinor }) }
                        items(rs, key = { it.id }) { r -> ReceiptRow(r, onClick = { onOpenReceipt(r.id, false) }) }
                    }
                }
            }
        }
    }

    if (sheet != Sheet.NONE) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
                when (sheet) {
                    Sheet.STORE -> {
                        Text("Bolt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
                        ListRow("Minden bolt", onClick = { vm.update { it.copy(store = null) }; sheet = Sheet.NONE })
                        if (stores.isEmpty()) Text("Még nincs rögzített bolt.", modifier = Modifier.padding(Spacing.xl), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stores.forEach { s -> ListRow(s.name, subtitle = s.address, onClick = { vm.update { it.copy(store = s) }; sheet = Sheet.NONE }) }
                    }
                    Sheet.DATE -> {
                        Text("Időszak", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
                        DatePreset.entries.filter { it != DatePreset.CUSTOM }.forEach { p ->
                            ListRow(p.label, onClick = { vm.update { it.copy(preset = p) }; sheet = Sheet.NONE },
                                trailing = { if (f.preset == p) Icon(Icons.Outlined.Check, contentDescription = "Kiválasztva") })
                        }
                        Text("Egyéni időszak", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = Spacing.xl, top = Spacing.lg))
                        var from by remember { mutableStateOf(f.customFrom) }
                        var to by remember { mutableStateOf(f.customTo) }
                        Row(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            DateField(from, { from = it }, "Ettől", Modifier.weight(1f))
                            DateField(to, { to = it }, "Eddig", Modifier.weight(1f))
                        }
                        Button(onClick = { vm.update { it.copy(preset = DatePreset.CUSTOM, customFrom = from, customTo = to) }; sheet = Sheet.NONE },
                            enabled = from != null && to != null, modifier = Modifier.padding(horizontal = Spacing.xl).fillMaxWidth()) { Text("Alkalmazás") }
                    }
                    Sheet.AMOUNT -> {
                        Text("Végösszeg", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
                        var min by remember { mutableStateOf(f.minText) }
                        var max by remember { mutableStateOf(f.maxText) }
                        Row(Modifier.padding(horizontal = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            MoneyField(min, { min = it }, "Legalább", Modifier.weight(1f))
                            MoneyField(max, { max = it }, "Legfeljebb", Modifier.weight(1f))
                        }
                        Button(onClick = { vm.update { it.copy(minText = min, maxText = max) }; sheet = Sheet.NONE },
                            modifier = Modifier.padding(horizontal = Spacing.xl).fillMaxWidth()) { Text("Alkalmazás") }
                    }
                    Sheet.NONE -> Unit
                }
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, total: Long?) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (total != null) Text(Fmt.money(total), style = MaterialTheme.typography.titleSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
