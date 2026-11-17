package hu.kiadaskoveto.feature.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.repo.BudgetProgress
import hu.kiadaskoveto.data.repo.BudgetScope
import hu.kiadaskoveto.database.model.ProductStat
import hu.kiadaskoveto.database.model.ReceiptSummary
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.feature.common.ReceiptRow
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ColumnChart
import hu.kiadaskoveto.ui.components.ColumnDatum
import hu.kiadaskoveto.ui.components.DeltaBadge
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.Meter
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.components.RankedBars
import hu.kiadaskoveto.ui.components.RankedRow
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal
import java.time.YearMonth

data class DashboardState(
    val loaded: Boolean = false,
    val month: YearMonth = YearMonth.now(),
    val total: Money = Money.ZERO,
    val count: Int = 0,
    val previousTotal: Money = Money.ZERO,
    val change: BigDecimal? = null,
    val savings: Money = Money.ZERO,
    val categories: List<Pair<Category?, Long>> = emptyList(),
    val trend: List<Pair<YearMonth, Long>> = emptyList(),
    val recent: List<ReceiptSummary> = emptyList(),
    val topProducts: List<ProductStat> = emptyList(),
    val pending: Int = 0,
    val budgets: List<BudgetProgress> = emptyList(),
    val hasAnyReceipt: Boolean = false,
) {
    val average: Money get() = PriceMath.average(total, count).roundedToForint()
}

class DashboardViewModel(c: AppContainer) : ViewModel() {
    private val month = YearMonth.now()
    private val period = Period.month(month)
    private val previous = Period.month(month.minusMonths(1))
    private val trendPeriod = Period.months(month.minusMonths(5), month)

    private val head = combine(
        c.analytics.periodSummary(period), c.analytics.periodSummary(previous), c.analytics.savings(period), c.receipts.pendingCount, c.analytics.firstPurchase,
    ) { cur, prev, savings, pending, first -> arrayOf<Any?>(cur, prev, savings, pending, first) }

    private val body = combine(
        c.analytics.categoryTotals(period), c.categories.categories, c.analytics.monthlyTotals(trendPeriod), c.receipts.recent(5),
        c.analytics.productStats(Period.months(month.minusMonths(2), month), "COUNT", 5),
    ) { cats, categories, trend, recent, products -> arrayOf<Any?>(cats, categories, trend, recent, products) }

    val state: StateFlow<DashboardState> = combine(head, body, c.budgets.budgets) { h, b, _ ->
        val cur = h[0] as hu.kiadaskoveto.database.model.PeriodSummary
        val prev = h[1] as hu.kiadaskoveto.database.model.PeriodSummary
        @Suppress("UNCHECKED_CAST") val cats = b[0] as List<hu.kiadaskoveto.database.model.CategoryTotal>
        @Suppress("UNCHECKED_CAST") val categories = b[1] as List<Category>
        @Suppress("UNCHECKED_CAST") val trend = b[2] as List<hu.kiadaskoveto.database.model.PeriodTotal>
        @Suppress("UNCHECKED_CAST") val recent = b[3] as List<ReceiptSummary>
        @Suppress("UNCHECKED_CAST") val products = b[4] as List<ProductStat>
        val byId = categories.associateBy { it.id }
        val trendMap = trend.associate { it.period to it.total }
        DashboardState(
            loaded = true,
            month = month,
            total = Money(cur.total),
            count = cur.count,
            previousTotal = Money(prev.total),
            change = PriceMath.percentChange(Money(prev.total), Money(cur.total)),
            savings = Money(h[2] as Long),
            categories = cats.filter { it.total > 0 }.map { byId[it.categoryId] to it.total },
            trend = (5 downTo 0).map { month.minusMonths(it.toLong()) }.map { it to (trendMap[it.toString()] ?: 0L) },
            recent = recent,
            topProducts = products,
            pending = h[3] as Int,
            budgets = c.budgets.progress(),
            hasAnyReceipt = h[4] != null || recent.isNotEmpty() || (h[3] as Int) > 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
}

@Composable
fun DashboardScreen(
    onAddReceipt: () -> Unit,
    onOpenReceipt: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onOpenBudgets: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: DashboardViewModel = viewModel { DashboardViewModel(c) }
    val s by vm.state.collectAsState()
    if (!s.loaded) { hu.kiadaskoveto.ui.components.LoadingState(); return }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg, bottom = 104.dp),
    ) {
        item { Hero(s, showComparison = s.hasAnyReceipt) }

        if (!s.hasAnyReceipt) {
            item {
                AppCard(Modifier.fillMaxWidth().padding(top = Spacing.xl)) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                        title = "Még nincs blokkod",
                        message = "Fényképezd le az első bevásárlásod blokkját, vagy importáld képként, PDF-ként. A feldolgozás teljesen a telefonodon történik.",
                        primaryAction = "Első blokk rögzítése",
                        onPrimary = onAddReceipt,
                    )
                }
            }
            return@LazyColumn
        }

        if (s.pending > 0) {
            item {
                Notice(
                    "${s.pending} blokk jóváhagyásra vagy ellenőrzésre vár.",
                    Modifier.padding(top = Spacing.lg),
                    action = "Megnézem", onAction = onOpenReceipts,
                )
            }
        }

        val budget = s.budgets.firstOrNull { it.budget.scope == BudgetScope.TOTAL.name } ?: s.budgets.firstOrNull()
        if (budget != null) {
            item {
                SectionHeader("Költségkeret", action = "Keretek", onAction = onOpenBudgets)
                AppCard(Modifier.fillMaxWidth(), onClick = onOpenBudgets) { BudgetSummary(budget) }
            }
        }

        if (s.categories.isNotEmpty()) {
            item {
                SectionHeader("Kategóriák", action = "Részletek", onAction = onOpenStats)
                AppCard(Modifier.fillMaxWidth()) {
                    val top = s.categories.take(5)
                    val rest = s.categories.drop(5).sumOf { it.second }
                    val rows = top.map { (cat, total) -> RankedRow(cat?.name ?: "Nem besorolt", total, leading = { CategoryBadge(cat, 36.dp) }) } +
                        if (rest > 0) listOf(RankedRow("Többi kategória (${s.categories.size - 5})", rest, leading = { CategoryBadge(null, 36.dp) })) else emptyList()
                    RankedBars(rows, total = s.categories.sumOf { it.second })
                    if (s.savings.isNegative) {
                        Spacer(Modifier.height(Spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Savings, contentDescription = null, tint = AppTheme.extended.positive, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Kedvezményekkel megtakarítva: ${Fmt.money(-s.savings)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (s.trend.any { it.second > 0 }) item {
            SectionHeader("Az elmúlt hat hónap", action = "Statisztika", onAction = onOpenStats)
            AppCard(Modifier.fillMaxWidth()) {
                ColumnChart(
                    data = s.trend.map { (ym, v) -> ColumnDatum(Fmt.monthShort(ym), v, emphasized = ym == s.month, tooltipTitle = Fmt.monthTitle(ym)) },
                    summary = "Havi költés az elmúlt hat hónapban: " + s.trend.joinToString("; ") { (ym, v) -> "${Fmt.monthTitle(ym)} ${Fmt.money(v)}" },
                )
            }
        }

        if (s.recent.isNotEmpty()) {
            item { SectionHeader("Legutóbbi blokkok", action = "Összes", onAction = onOpenReceipts) }
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.sm)) {
                    s.recent.forEach { r -> ReceiptRow(r, onClick = { if (r.status == "CONFIRMED") onOpenReceipt(r.id) else onOpenReview(r.id) }) }
                }
            }
        }

        if (s.topProducts.isNotEmpty()) {
            item { SectionHeader("Gyakran vásárolt") }
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.sm)) {
                    s.topProducts.forEach { p ->
                        hu.kiadaskoveto.ui.components.ListRow(
                            title = p.name,
                            subtitle = "${p.purchaseCount}× az elmúlt 3 hónapban",
                            trailing = { MoneyText(Money(p.total), style = MaterialTheme.typography.bodyMedium) },
                            onClick = p.productId?.let { id -> { onOpenProduct(id) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(s: DashboardState, showComparison: Boolean) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(s.total) { anim.animateTo(1f, tween(700, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
    val shown = Money((s.total.minor * anim.value).toLong() / 100 * 100)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "${Fmt.monthTitle(s.month)}: elköltve ${Fmt.money(s.total)}, ${s.count} blokk"
    }) {
        Text(Fmt.monthTitle(s.month), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.xs))
        Text(
            Fmt.money(if (anim.value >= 1f) s.total else shown),
            style = MaterialTheme.typography.displayMedium,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
        Spacer(Modifier.height(Spacing.sm))
        if (showComparison) {
            DeltaBadge(s.change, caption = "az előző hónaphoz képest")
            Spacer(Modifier.height(Spacing.xs))
        }
        if (showComparison) Text(
            if (s.count == 0) "Ebben a hónapban még nincs jóváhagyott blokk"
            else "${s.count} blokk · átlagos kosár ${Fmt.money(s.average)}",
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BudgetSummary(p: BudgetProgress) {
    val ext = AppTheme.extended
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.title} · ${p.budget.period.let { if (it == "WEEKLY") "heti" else "havi" }} keret", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${p.percent}%", style = MaterialTheme.typography.titleSmall.tabular)
        }
        Meter(p.ratio)
        val (icon, tint, label) = when (p.level) {
            BudgetProgress.Level.OK -> Triple(Icons.Outlined.CheckCircle, ext.positive, "Hátralévő: ${Fmt.money(p.remaining)}")
            BudgetProgress.Level.WARNING, BudgetProgress.Level.CRITICAL -> Triple(Icons.Outlined.ReportProblem, ext.warning, "Közeledsz a kerethez · hátralévő: ${Fmt.money(p.remaining)}")
            BudgetProgress.Level.OVER -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "Túllépve: ${Fmt.money(-p.remaining)}")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${Fmt.money(p.spent)} / ${Fmt.money(p.limit)}", style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
