package hu.kiadaskoveto.data.repo

import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.settings.AppSettings
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.entity.BudgetAlertEntity
import hu.kiadaskoveto.database.entity.BudgetEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields

enum class BudgetScope(val label: String) { TOTAL("Teljes költés"), CATEGORY("Kategória"), STORE("Bolt") }
enum class BudgetPeriod(val label: String) { MONTHLY("Havi"), WEEKLY("Heti") }

data class BudgetProgress(
    val budget: BudgetEntity,
    val title: String,
    val spent: Money,
    val limit: Money,
    val periodLabel: String,
) {
    val ratio: Double get() = if (limit.minor <= 0) 0.0 else spent.minor.toDouble() / limit.minor
    val percent: Int get() = (ratio * 100).toInt()
    val remaining: Money get() = limit - spent
    val level: Level get() = when { ratio >= 1.0 -> Level.OVER; ratio >= 0.9 -> Level.CRITICAL; ratio >= 0.75 -> Level.WARNING; else -> Level.OK }

    enum class Level { OK, WARNING, CRITICAL, OVER }
}

data class BudgetAlert(val progress: BudgetProgress, val threshold: Int)

/** Monthly / weekly budgets with exact spend calculation and one-time threshold alerts. */
class BudgetRepository(private val db: KiadasDatabase, private val settings: suspend () -> AppSettings) {

    private val dao = db.budgetDao()
    private val analytics = db.analyticsDao()

    val budgets = dao.observeAll()

    suspend fun save(budget: BudgetEntity): Long {
        require(budget.limitMinor > 0) { "A keret összegének nagyobbnak kell lennie nullánál." }
        return if (budget.id == 0L) dao.insert(budget) else { dao.update(budget); budget.id }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    fun periodFor(budget: BudgetEntity, today: LocalDate = LocalDate.now()): Pair<Period, String> =
        if (budget.period == BudgetPeriod.WEEKLY.name) {
            Period.week(today) to "${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}. hét"
        } else Period.month(YearMonth.from(today)) to "e hónap"

    private fun periodKey(budget: BudgetEntity, today: LocalDate): String =
        if (budget.period == BudgetPeriod.WEEKLY.name) "${today.get(IsoFields.WEEK_BASED_YEAR)}-W${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        else YearMonth.from(today).toString()

    /** Current progress of every budget. Category budgets include their subcategories. */
    suspend fun progress(today: LocalDate = LocalDate.now()): List<BudgetProgress> {
        val categories = db.categoryDao().all()
        val stores = db.storeDao().all().associateBy { it.id }
        return dao.all().map { b ->
            val (period, label) = periodFor(b, today)
            val spent: Long = when (b.scope) {
                BudgetScope.CATEGORY.name -> {
                    val ids = setOf(b.categoryId) + categories.filter { it.parentId == b.categoryId }.map { it.id }
                    analytics.categoryTotals(period.from, period.to).filter { it.categoryId in ids }.sumOf { it.total }
                }
                BudgetScope.STORE.name -> analytics.storeTotals(period.from, period.to).filter { it.storeId == b.storeId }.sumOf { it.total }
                else -> analytics.periodSummary(period.from, period.to).total
            }
            val title = when (b.scope) {
                BudgetScope.CATEGORY.name -> categories.firstOrNull { it.id == b.categoryId }?.name ?: "Törölt kategória"
                BudgetScope.STORE.name -> stores[b.storeId]?.name ?: "Törölt bolt"
                else -> "Teljes költés"
            }
            BudgetProgress(b, title, Money(spent), Money(b.limitMinor), label)
        }
    }

    /** Thresholds crossed for the first time in the current period (each fires once per period). */
    suspend fun newAlerts(today: LocalDate = LocalDate.now()): List<BudgetAlert> {
        val s = settings()
        if (!s.budgetNotifications) return emptyList()
        val thresholds = s.budgetThresholds.sorted()
        val out = ArrayList<BudgetAlert>()
        for (p in progress(today)) {
            val key = periodKey(p.budget, today)
            val crossed = thresholds.filter { p.percent >= it }
            val highest = crossed.maxOrNull() ?: continue
            var isNew = false
            for (t in crossed) {
                if (dao.alertCount(p.budget.id, key, t) == 0) {
                    dao.insertAlert(BudgetAlertEntity(budgetId = p.budget.id, periodKey = key, thresholdPercent = t, notifiedAt = System.currentTimeMillis()))
                    isNew = true
                }
            }
            if (isNew) out += BudgetAlert(p, highest)
        }
        return out
    }
}
