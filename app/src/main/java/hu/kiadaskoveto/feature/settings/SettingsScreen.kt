package hu.kiadaskoveto.feature.settings

import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import hu.kiadaskoveto.MainActivity
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onOpenBudgets: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenStores: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLocalAi: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val s by c.settings.settings.collectAsState(initial = null)
    val storageBytes by produceState(0L) { value = withContext(Dispatchers.IO) { c.storage.totalSizeBytes() } }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var deleteStep by remember { mutableStateOf(0) }
    val settings = s ?: return

    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.section)) {
        item { Text("Beállítások", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Spacing.lg)) }

        item {
            SectionHeader("Megjelenés")
            AppCard(Modifier.fillMaxWidth()) {
                Text("Téma", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(ThemeMode.SYSTEM to "Rendszer", ThemeMode.LIGHT to "Világos", ThemeMode.DARK to "Sötét").forEach { (m, l) ->
                        FilterChip(selected = settings.themeMode == m, onClick = { scope.launch { c.settings.setThemeMode(m) } }, label = { Text(l) })
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SwitchRow("Háttérkép színei (Material You)", "A rendszer színpalettáját használja a KiadásKövető saját színei helyett.",
                    settings.dynamicColor) { scope.launch { c.settings.setDynamicColor(it) } }
            }
        }

        item {
            SectionHeader("Adatok és szabályok")
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                NavRow(Icons.Outlined.Savings, "Költségkeretek", "Havi és heti keretek, figyelmeztetések", onOpenBudgets)
                NavRow(Icons.Outlined.Category, "Kategóriák", "Hozzáadás, átnevezés, alkategóriák", onOpenCategories)
                NavRow(Icons.Outlined.Rule, "Kategorizálási szabályok", "Saját szabályok és megjegyzett javítások", onOpenRules)
                NavRow(Icons.Outlined.Storefront, "Boltok", "Átnevezés, összevonás", onOpenStores)
                NavRow(Icons.Outlined.Backup, "Biztonsági mentés és exportálás", "Mentés, visszaállítás, CSV, JSON, PDF", onOpenBackup)
            }
        }

        item {
            SectionHeader("Felismerés")
            AppCard(Modifier.fillMaxWidth()) {
                val accurate = settings.ocrLanguages.startsWith("hun_best")
                val english = settings.ocrLanguages.endsWith("+eng")
                fun langs(acc: Boolean, eng: Boolean) = (if (acc) "hun_best" else "hun") + if (eng) "+eng" else ""
                Text("Felismerési mód", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = !accurate, onClick = { scope.launch { c.settings.setOcrLanguages(langs(false, english)) } }, label = { Text("Gyors") })
                    FilterChip(selected = accurate, onClick = { scope.launch { c.settings.setOcrLanguages(langs(true, english)) } }, label = { Text("Pontos (lassabb)") })
                }
                Text("Nyelv", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = !english, onClick = { scope.launch { c.settings.setOcrLanguages(langs(accurate, false)) } }, label = { Text("Magyar") })
                    FilterChip(selected = english, onClick = { scope.launch { c.settings.setOcrLanguages(langs(accurate, true)) } }, label = { Text("Magyar + angol") })
                }
                Text("A nyelvi modellek az alkalmazásba vannak építve (Tesseract 5), letöltés nem szükséges. A pontos mód nagyobb modellt használ, " +
                    "gyengébb telefonon többszörös ideig tarthat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Automatikus vágás", "A blokk széleinek felismerése a fotón.", settings.autoCrop) { scope.launch { c.settings.setAutoCrop(it) } }
            }
            AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                NavRow(Icons.Outlined.Memory, "Helyi AI (opcionális)", "Saját GGUF modell importálása, kompatibilitás", onOpenLocalAi)
            }
        }

        item {
            SectionHeader("Biztonság és adatvédelem")
            AppCard(Modifier.fillMaxWidth()) {
                val activity = context as? MainActivity
                val canLock = activity?.canUseAppLock() == true
                SwitchRow("Alkalmazászár", if (canLock) "Ujjlenyomat, arc vagy képernyőzár a megnyitáskor." else "Ehhez állíts be képernyőzárat a telefonon.",
                    settings.appLock, enabled = canLock) { scope.launch { c.settings.setAppLock(it) } }
            }
            AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                NavRow(Icons.Outlined.Policy, "Adatvédelem", "Hol vannak az adataid, mit nem gyűjtünk", onOpenPrivacy)
                NavRow(Icons.Outlined.VerifiedUser, "Adatbázis ellenőrzése", "Integritás, kapcsolatok, összegek és fájlok") {
                    scope.launch { report = runCatching { c.runConsistencyCheck() }.getOrElse { listOf("Hiba: ${it.message}") } }
                }
                ListRow("Tárolt eredeti fájlok", subtitle = Fmt.bytes(storageBytes))
                NavRow(Icons.Outlined.DeleteForever, "Összes adat törlése", "Blokkok, termékek, keretek, beállítások és fájlok") { deleteStep = 1 }
            }
        }

        item {
            SectionHeader("Az alkalmazásról")
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                NavRow(Icons.Outlined.Info, "Névjegy és licencek", "Verzió, nyílt forrású összetevők", onOpenAbout)
            }
        }
    }

    report?.let { lines ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("Adatbázis ellenőrzése") }, text = { Text(lines.joinToString("\n")) },
            confirmButton = { TextButton(onClick = { report = null }) { Text("Rendben") } })
    }
    if (deleteStep == 1) ConfirmDialog("Összes adat törlése?",
        "Minden blokk, tétel, termék, keret, szabály, beállítás és eredeti fájl véglegesen törlődik erről a telefonról. Ha meg szeretnéd tartani őket, előbb készíts biztonsági mentést.",
        "Tovább", onConfirm = { deleteStep = 2 }, onDismiss = { if (deleteStep == 1) deleteStep = 0 }, destructive = true)
    if (deleteStep == 2) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("Végleges törlés") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("A megerősítéshez írd be: TÖRLÉS")
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim().equals("TÖRLÉS", ignoreCase = true) || typed.trim().equals("TORLES", ignoreCase = true), onClick = {
                    deleteStep = 0
                    scope.launch { c.deleteAllData(); snackbar.showSnackbar("Minden adat törölve.") }
                }) { Text("Törlés", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("Mégse") } },
        )
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListRow(title, subtitle = subtitle, leading = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onClick)
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
