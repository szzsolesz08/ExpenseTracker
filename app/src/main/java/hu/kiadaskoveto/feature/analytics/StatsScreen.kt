package hu.kiadaskoveto.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.repo.PriceChange
import hu.kiadaskoveto.data.repo.PriceNormalizer
import hu.kiadaskoveto.database.model.CategoryPeriodTotal
import hu.kiadaskoveto.database.model.CategoryTotal
import hu.kiadaskoveto.database.model.PeriodSummary
import hu.kiadaskoveto.database.model.PeriodTotal
import hu.kiadaskoveto.database.model.PricePoint
import hu.kiadaskoveto.database.model.ProductStat
import hu.kiadaskoveto.database.model.ProductWithStats
import hu.kiadaskoveto.database.model.StoreTotal
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ColumnChart
import hu.kiadaskoveto.ui.components.ColumnDatum
import hu.kiadaskoveto.ui.components.DeltaBadge
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.components.RankedBars
import hu.kiadaskoveto.ui.components.RankedRow
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

data class StatsState(
    val month: YearMonth,
    val summary: PeriodSummary = PeriodSummary(0, 0),
    val previous: PeriodSummary = PeriodSummary(0, 0),
    val daily: List<PeriodTotal> = emptyList(),
    val months: List<PeriodTotal> = emptyList(),
    val categories: List<CategoryTotal> = emptyList(),
    val categoryMonthly: List<CategoryPeriodTotal> = emptyList(),
    val receiptDiscounts: Long = 0,
    val byCount: List<ProductStat> = emptyList(),
    val byTotal: List<ProductStat> = emptyList(),
    val byMaxPrice: List<ProductStat> = emptyList(),
    val stores: List<StoreTotal> = emptyList(),
    val pricePoints: List<PricePoint> = emptyList(),
    val products: List<ProductWithStats> = emptyList(),
    val categoryList: List<Category> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())

    /** Months that have confirmed receipts, newest first (for the month picker). */
    val availableMonths: StateFlow<List<PeriodTotal>> = c.analytics.monthlyTotals(Period.ALL)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun <T> monthFlow(f: (YearMonth) -> Flow<T>): Flow<T> = month.flatMapLatest(f)

    private val a = combine(
        monthFlow { c.analytics.periodSummary(Period.month(it)) },
        monthFlow { c.analytics.periodSummary(Period.month(it.minusMonths(1))) },
        monthFlow { c.analytics.dailyTotals(Period.month(it)) },
        monthFlow { c.analytics.monthlyTotals(Period.months(it.minusMonths(11), it)) },
        monthFlow { c.analytics.categoryTotals(Period.month(it)) },
    ) { s, p, d, m, cat -> arrayOf<Any>(s, p, d, m, cat) }

    private val b = combine(
        monthFlow { c.analytics.categoryMonthly(Period.months(it.minusMonths(5), it)) },
        monthFlow { c.analytics.receiptLevelDiscounts(Period.month(it)) },
        monthFlow { c.analytics.productStats(Period.month(it), "COUNT", 10) },
        monthFlow { c.analytics.productStats(Period.month(it), "TOTAL", 10) },
        monthFlow { c.analytics.productStats(Period.month(it), "MAX_PRICE", 10) },
    ) { cm, rd, bc, bt, bm -> arrayOf<Any>(cm, rd, bc, bt, bm) }

    private val d = combine(
        monthFlow { c.analytics.storeTotals(Period.month(it)) },
        c.analytics.allPricePoints,
        c.products.productsWithStats,
        c.categories.categories,
    ) { st, pp, pr, cats -> arrayOf<Any>(st, pp, pr, cats) }

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<StatsState?> = combine(month, a, b, d) { m, x, y, z ->
        StatsState(
            month = m, summary = x[0] as PeriodSummary, previous = x[1] as PeriodSummary, daily = x[2] as List<PeriodTotal>,
            months = x[3] as List<PeriodTotal>, categories = x[4] as List<CategoryTotal>, categoryMonthly = y[0] as List<CategoryPeriodTotal>,
            receiptDiscounts = y[1] as Long, byCount = y[2] as List<ProductStat>, byTotal = y[3] as List<ProductStat>, byMaxPrice = y[4] as List<ProductStat>,
            stores = z[0] as List<StoreTotal>, pricePoints = z[1] as List<PricePoint>, products = z[2] as List<ProductWithStats>, categoryList = z[3] as List<Category>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

private val tabs = listOf("Havi", "Kategóriák", "Termékek", "Árak", "Boltok")

@Composable
fun StatsScreen(onOpenProduct: (Long) -> Unit) {
    val c = LocalAppContainer.current
    val vm: StatsViewModel = viewModel { StatsViewModel(c) }
    val s by vm.state.collectAsState()
    val month by vm.month.collectAsState()
    val months by vm.availableMonths.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var pickMonth by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.sm, top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text("Statisztika", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.month.value = month.minusMonths(1) }) { Icon(Icons.Outlined.ChevronLeft, contentDescription = "Előző hónap") }
            androidx.compose.material3.TextButton(onClick = { pickMonth = true }) { Text(Fmt.monthTitle(month), style = MaterialTheme.typography.titleSmall) }
            IconButton(onClick = { vm.month.value = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Icon(Icons.Outlined.ChevronRight, contentDescription = "Következő hónap") }
        }
        if (pickMonth) {
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { pickMonth = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                    Text("Hónap választása", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
                    ListRow(Fmt.monthTitle(YearMonth.now()), subtitle = "Aktuális hónap", onClick = { vm.month.value = YearMonth.now(); pickMonth = false })
                    if (months.isEmpty()) Text("Még nincs jóváhagyott blokk.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.xl))
                    months.sortedByDescending { it.period }.forEach { m ->
                        val ym = YearMonth.parse(m.period)
                        ListRow(Fmt.monthTitle(ym), subtitle = "${m.count} blokk", trailing = { MoneyText(Money(m.total), style = MaterialTheme.typography.bodyMedium) },
                            onClick = { vm.month.value = ym; pickMonth = false })
                    }
                }
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = Spacing.gutter, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        val st = s ?: return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.section)) {
            when (tab) {
                0 -> monthly(st)
                1 -> categories(st)
                2 -> products(st, onOpenProduct)
                3 -> prices(st, onOpenProduct)
                else -> stores(st, onOpenProduct)
            }
        }
    }
}

private fun LazyListScope.noData(text: String = "Ebben a hónapban nincs jóváhagyott blokk.") {
    item { EmptyState(Icons.Outlined.BarChart, "Nincs adat", text) }
}

private fun LazyListScope.monthly(s: StatsState) {
    if (s.summary.count == 0) { noData(); return }
    item {
        AppCard(Modifier.fillMaxWidth().padding(top = Spacing.md)) {
            Text("Havi költés", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(Money(s.summary.total), style = MaterialTheme.typography.displaySmall, aligned = false)
            DeltaBadge(PriceMath.percentChange(Money(s.previous.total), Money(s.summary.total)), "az előző hónaphoz képest")
            Spacer(Modifier.height(Spacing.md))
            val days = c_daysForAverage(s.month)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Figure("Napi átlag", if (days > 0) PriceMath.average(Money(s.summary.total), days).roundedToForint() else Money.ZERO, Modifier.weight(1f))
                Figure("Átlagos kosár", PriceMath.average(Money(s.summary.total), s.summary.count).roundedToForint(), Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    Text("Blokkok", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.summary.count.toString(), style = MaterialTheme.typography.titleMedium.tabular)
                }
            }
        }
    }
    if (s.daily.isNotEmpty()) item {
        SectionHeader("Napi költés")
        AppCard(Modifier.fillMaxWidth()) {
            val byDay = s.daily.associate { LocalDate.parse(it.period).dayOfMonth to it.total }
            val max = byDay.maxByOrNull { it.value }?.key
            val data = (1..s.month.lengthOfMonth()).map { d ->
                ColumnDatum(d.toString(), byDay[d] ?: 0, emphasized = d == max, tooltipTitle = Fmt.date(s.month.atDay(d)))
            }
            ColumnChart(data, summary = "Napi költés " + s.daily.joinToString("; ") { "${it.period}: ${Fmt.money(it.total)}" }, labelEvery = 5)
            Text("A legtöbbet költött nap kiemelve. Koppints egy oszlopra az összegért.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionHeader("Heti bontás")
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            val wf = WeekFields.of(Fmt.HU)
            s.daily.groupBy { LocalDate.parse(it.period).get(wf.weekOfMonth()) }.toSortedMap().forEach { (w, list) ->
                val first = list.minOf { LocalDate.parse(it.period) }; val last = list.maxOf { LocalDate.parse(it.period) }
                ListRow("$w. hét", subtitle = "${Fmt.dateShort(first)} – ${Fmt.dateShort(last)} · ${list.sumOf { it.count }} blokk",
                    trailing = { MoneyText(Money(list.sumOf { it.total }), style = MaterialTheme.typography.bodyMedium) })
            }
        }
    }
    item {
        SectionHeader("Az elmúlt 12 hónap")
        AppCard(Modifier.fillMaxWidth()) {
            val map = s.months.associate { it.period to it.total }
            val data = (11 downTo 0).map { s.month.minusMonths(it.toLong()) }.map { ym ->
                ColumnDatum(Fmt.monthShort(ym), map[ym.toString()] ?: 0, emphasized = ym == s.month, tooltipTitle = Fmt.monthTitle(ym))
            }
            ColumnChart(data, summary = "Havi költés az elmúlt 12 hónapban", labelEvery = 2)
            val nonZero = s.months.filter { it.total > 0 }
            if (nonZero.size >= 2) {
                Text("Havi átlag: ${Fmt.money(PriceMath.average(Money(nonZero.sumOf { it.total }), nonZero.size).roundedToForint())} (${nonZero.size} hónap adatai alapján)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
    }
}

private fun c_daysForAverage(ym: YearMonth): Int {
    val today = LocalDate.now()
    return when {
        today.isBefore(ym.atDay(1)) -> 0
        YearMonth.from(today) == ym -> today.dayOfMonth
        else -> ym.lengthOfMonth()
    }
}

@Composable
private fun Figure(label: String, value: Money, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun LazyListScope.categories(s: StatsState) {
    val byId = s.categoryList.associateBy { it.id }
    val rows = s.categories.filter { it.total > 0 }
    if (rows.isEmpty()) { noData(); return }
    item {
        var selected by remember { mutableStateOf(rows.first().categoryId) }
        SectionHeader("Megoszlás")
        AppCard(Modifier.fillMaxWidth()) {
            RankedBars(rows.map { ct -> RankedRow(byId[ct.categoryId]?.name ?: "Nem besorolt", ct.total, leading = { CategoryBadge(byId[ct.categoryId], 36.dp) }, onClick = { selected = ct.categoryId }) })
            if (s.receiptDiscounts != 0L) Text("Blokkszintű kedvezmények (kategóriához nem köthető): ${Fmt.money(s.receiptDiscounts)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
        }
        val name = byId[selected]?.name ?: "Nem besorolt"
        SectionHeader("Havi alakulás: $name")
        AppCard(Modifier.fillMaxWidth()) {
            val map = s.categoryMonthly.filter { it.categoryId == selected }.associate { it.period to it.total }
            ColumnChart(
                (5 downTo 0).map { s.month.minusMonths(it.toLong()) }.map { ym -> ColumnDatum(Fmt.monthShort(ym), map[ym.toString()] ?: 0, ym == s.month, Fmt.monthTitle(ym)) },
                summary = "$name havi költése az elmúlt hat hónapban",
            )
            Text("Koppints egy kategóriára fent a trendje megjelenítéséhez.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.products(s: StatsState, onOpenProduct: (Long) -> Unit) {
    if (s.byCount.isEmpty()) { noData(); return }
    item {
        var mode by remember { mutableIntStateOf(0) }
        Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf("Leggyakoribb", "Legtöbb költés", "Legdrágább").forEachIndexed { i, t -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(t) }) }
        }
        val list = when (mode) { 0 -> s.byCount; 1 -> s.byTotal; else -> s.byMaxPrice }
        AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            list.forEach { p ->
                ListRow(
                    p.name,
                    subtitle = when (mode) {
                        2 -> "Legdrágább tétel ebben a hónapban · ${p.purchaseCount}× vásárolva"
                        else -> "${p.purchaseCount}× · ${p.receiptCount} blokkon"
                    },
                    trailing = { MoneyText(Money(if (mode == 2) p.maxPrice else p.total), style = MaterialTheme.typography.bodyMedium) },
                    onClick = p.productId?.let { id -> { onOpenProduct(id) } },
                )
            }
        }
        val repeated = s.byCount.filter { it.receiptCount >= 2 }
        if (repeated.isNotEmpty()) {
            SectionHeader("Rendszeresen vásárolt ebben a hónapban")
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                repeated.forEach { p -> ListRow(p.name, subtitle = "${p.receiptCount} külön vásárláskor", trailing = { MoneyText(Money(p.total), style = MaterialTheme.typography.bodyMedium) },
                    onClick = p.productId?.let { id -> { onOpenProduct(id) } }) }
            }
        }
    }
}

private fun LazyListScope.prices(s: StatsState, onOpenProduct: (Long) -> Unit) {
    val names = s.products.associate { it.product.id to it.product.name }
    val changes: List<PriceChange> = PriceNormalizer.changes(s.pricePoints, names).filter { !it.difference.isZero }.sortedByDescending { it.current.date }
    if (changes.isEmpty()) {
        noData("Árváltozás akkor jelenik meg, ha ugyanazt a terméket legalább kétszer vásároltad azonos kiszerelésben vagy egységben.")
        return
    }
    item {
        SectionHeader("Legutóbbi árváltozások")
        Text("Csak azonos alapon összevethető vásárlások (Ft/kg, Ft/l, vagy azonos kiszerelés) szerepelnek; a kedvezmények nélküli polci árral.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            changes.forEach { ch ->
                val up = ch.difference.isPositive
                val color = if (up) MaterialTheme.colorScheme.error else AppTheme.extended.positive
                ListRow(
                    ch.productName,
                    subtitle = "${Fmt.money(ch.previous.price)} → ${Fmt.money(ch.current.price)} · ${ch.current.basis.label} · ${Fmt.dateShort(ch.current.date.toLocalDate())}",
                    leading = { Icon(if (up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, contentDescription = if (up) "Drágult" else "Olcsóbb lett", tint = color) },
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (up) "+" else "") + Fmt.money(ch.difference), style = MaterialTheme.typography.bodyMedium.tabular)
                            ch.percent?.let { Text(Fmt.signedPercent(it), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    onClick = { onOpenProduct(ch.productId) },
                )
            }
        }
    }
}

private fun LazyListScope.stores(s: StatsState, onOpenProduct: (Long) -> Unit) {
    if (s.stores.isEmpty()) { noData(); return }
    item {
        SectionHeader("Költés boltonként")
        AppCard(Modifier.fillMaxWidth()) { RankedBars(s.stores.map { RankedRow(it.storeName, it.total) }) }
        SectionHeader("Látogatások és átlagos kosár")
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            s.stores.forEach { st ->
                ListRow(st.storeName, subtitle = "${st.visits} vásárlás · átlagos kosár ${Fmt.money(PriceMath.average(Money(st.total), st.visits).roundedToForint())}",
                    trailing = { MoneyText(Money(st.total), style = MaterialTheme.typography.bodyMedium) })
            }
        }
    }
    // Comparable prices of the same product identity at different stores (latest price per store, same basis).
    val names = s.products.associate { it.product.id to it.product.name }
    val comparisons = s.pricePoints.filter { it.productId != null && it.storeId != null }.groupBy { it.productId!! }.mapNotNull { (pid, pts) ->
        val comparable = pts.mapNotNull { PriceNormalizer.comparable(it) }
        val basis = comparable.groupBy { it.basis.key }.maxByOrNull { it.value.map { c -> c.point.storeId }.distinct().size }?.value ?: return@mapNotNull null
        val latestPerStore = basis.groupBy { it.point.storeId }.mapValues { it.value.maxBy { c -> c.point.purchasedAt } }
        if (latestPerStore.size < 2) null else Triple(pid, names[pid] ?: "Termék", latestPerStore.values.sortedBy { it.price })
    }
    if (comparisons.isNotEmpty()) item {
        SectionHeader("Ugyanaz a termék több boltban")
        Text("Csak a megerősített, azonos termékként rögzített tételek, azonos kiszerelési alapon.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            comparisons.forEach { (pid, name, prices) ->
                ListRow(name,
                    subtitle = prices.joinToString(" · ") { "${it.point.storeName ?: "?"}: ${Fmt.money(it.price)}" } + " (${prices.first().basis.label})",
                    onClick = { onOpenProduct(pid) })
            }
        }
    }
}

