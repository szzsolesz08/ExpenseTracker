package hu.kiadaskoveto.feature.budget

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.data.repo.BudgetPeriod
import hu.kiadaskoveto.data.repo.BudgetProgress
import hu.kiadaskoveto.data.repo.BudgetScope
import hu.kiadaskoveto.data.work.WorkScheduler
import hu.kiadaskoveto.database.entity.BudgetEntity
import hu.kiadaskoveto.feature.dashboard.BudgetSummary
import hu.kiadaskoveto.feature.receipts.CategoryPickerSheet
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.MoneyField
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val budgets by c.budgets.budgets.collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState(initial = null)
    val progress by produceState(emptyList<BudgetProgress>(), budgets) { value = c.budgets.progress() }
    var editing by remember { mutableStateOf<BudgetEntity?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Költségkeretek") }, navigationIcon = { BackIcon(onBack) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                editing = BudgetEntity(scope = BudgetScope.TOTAL.name, period = BudgetPeriod.MONTHLY.name, limitMinor = 0, createdAt = 0, updatedAt = 0)
            }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Új keret") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = 104.dp)) {
            if (budgets.isEmpty()) item {
                EmptyState(Icons.Outlined.Savings, "Nincs beállított keret",
                    "Adj meg havi vagy heti keretet a teljes költésre, egy kategóriára vagy egy boltra. Értesítünk, ha eléred a beállított szinteket.")
            }
            items(progress, key = { it.budget.id }) { p ->
                AppCard(Modifier.fillMaxWidth().padding(top = Spacing.md), onClick = { editing = p.budget }) { BudgetSummary(p) }
            }
            item {
                val s = settings ?: return@item
                SectionHeader("Figyelmeztetések")
                AppCard(Modifier.fillMaxWidth()) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Értesítések", style = MaterialTheme.typography.bodyLarge)
                            Text("Helyi értesítés a telefonon; semmilyen adat nem hagyja el az eszközt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.budgetNotifications, onCheckedChange = { on ->
                            scope.launch { c.settings.setBudgetNotifications(on) }
                            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text("Figyelmeztetési szintek", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(50, 75, 90, 100).forEach { t ->
                            val on = t in s.budgetThresholds
                            FilterChip(selected = on, onClick = { scope.launch { c.settings.setBudgetThresholds(if (on) s.budgetThresholds - t else s.budgetThresholds + t) } }, label = { Text("$t%") })
                        }
                    }
                }
            }
        }
    }

    editing?.let { b ->
        BudgetEditor(b, onDismiss = { editing = null }, onSave = { saved ->
            scope.launch {
                runCatching { c.budgets.save(saved.copy(createdAt = if (saved.createdAt == 0L) System.currentTimeMillis() else saved.createdAt, updatedAt = System.currentTimeMillis())) }
                    .onSuccess { editing = null; WorkScheduler.checkBudgetsNow(c.context) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Mentés sikertelen") }
            }
        }, onDelete = if (b.id != 0L) ({ scope.launch { c.budgets.delete(b.id); editing = null } }) else null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetEditor(b: BudgetEntity, onDismiss: () -> Unit, onSave: (BudgetEntity) -> Unit, onDelete: (() -> Unit)?) {
    val c = LocalAppContainer.current
    val categories by c.categories.categories.collectAsState(initial = emptyList())
    val stores by c.stores.stores.collectAsState(initial = emptyList())
    var scopeSel by remember { mutableStateOf(BudgetScope.valueOf(b.scope)) }
    var period by remember { mutableStateOf(BudgetPeriod.valueOf(b.period)) }
    var categoryId by remember { mutableStateOf(b.categoryId) }
    var storeId by remember { mutableStateOf(b.storeId) }
    var amount by remember { mutableStateOf(if (b.limitMinor > 0) (b.limitMinor / 100).toString() else "") }
    var pickCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val limit = AmountParser.parseMoney(amount)
    val valid = limit != null && limit.isPositive && (scopeSel != BudgetScope.CATEGORY || categoryId != null) && (scopeSel != BudgetScope.STORE || storeId != null)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(if (b.id == 0L) "Új költségkeret" else "Keret szerkesztése", style = MaterialTheme.typography.titleLarge)
            Text("Mire vonatkozik?", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { BudgetScope.entries.forEach { s -> FilterChip(selected = scopeSel == s, onClick = { scopeSel = s }, label = { Text(s.label) }) } }
            when (scopeSel) {
                BudgetScope.CATEGORY -> ListRow(categories.firstOrNull { it.id == categoryId }?.name ?: "Kategória kiválasztása", onClick = { pickCategory = true })
                BudgetScope.STORE -> Column {
                    if (stores.isEmpty()) Text("Még nincs rögzített bolt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    stores.forEach { s -> FilterChip(selected = storeId == s.id, onClick = { storeId = s.id }, label = { Text(s.name) }) }
                }
                BudgetScope.TOTAL -> Unit
            }
            Text("Időszak", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { BudgetPeriod.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) } }
            MoneyField(amount, { amount = it }, "Keret összege", Modifier.fillMaxWidth())
            Button(enabled = valid, onClick = {
                onSave(b.copy(scope = scopeSel.name, period = period.name, limitMinor = limit!!.minor,
                    categoryId = if (scopeSel == BudgetScope.CATEGORY) categoryId else null, storeId = if (scopeSel == BudgetScope.STORE) storeId else null))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mentés") }
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Keret törlése", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (pickCategory) CategoryPickerSheet(categories, categoryId, onPick = { categoryId = it; pickCategory = false }, onDismiss = { pickCategory = false })
    if (confirmDelete && onDelete != null) ConfirmDialog("Keret törlése", "A keret és a hozzá tartozó figyelmeztetések törlődnek.", "Törlés", onConfirm = onDelete, onDismiss = { confirmDelete = false }, destructive = true)
}
