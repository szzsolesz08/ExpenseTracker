package hu.kiadaskoveto.feature.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.BuildConfig
import hu.kiadaskoveto.data.backup.BackupData
import hu.kiadaskoveto.data.work.WorkScheduler
import hu.kiadaskoveto.localai.InstalledModel
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val settings by c.settings.settings.collectAsState(initial = null)
    var busy by remember { mutableStateOf<String?>(null) }
    var restoreCandidate by remember { mutableStateOf<Pair<Uri, BackupData>?>(null) }
    var reportMonth by remember { mutableStateOf(YearMonth.now()) }
    val stamp = { LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) }

    fun job(label: String, block: suspend () -> String) = scope.launch {
        busy = label
        val msg = runCatching { block() }.getOrElse { "Hiba: ${it.message ?: "ismeretlen"}" }
        busy = null
        snackbar.showSnackbar(msg)
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        job("Mentés készítése…") { val s = c.backup.backupTo(uri); c.settings.setLastBackup(System.currentTimeMillis(), "Kézi mentés"); "Mentve: ${s.receipts} blokk, ${s.files} fájl" }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { runCatching { c.backup.inspect(uri) }.onSuccess { restoreCandidate = uri to it }.onFailure { snackbar.showSnackbar(it.message ?: "A mentés nem olvasható.") } }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        job("CSV exportálása…") { context.contentResolver.openOutputStream(uri)?.use { c.backup.exportCsv(it) }; "CSV exportálva" }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        job("JSON exportálása…") { context.contentResolver.openOutputStream(uri)?.use { c.backup.exportJson(it) }; "JSON exportálva" }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        job("PDF jelentés készítése…") { context.contentResolver.openOutputStream(uri)?.use { c.pdfReport.write(reportMonth, it) }; "PDF jelentés elkészült" }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        scope.launch { c.settings.setAutoBackupFolder(uri.toString()) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Mentés és exportálás") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.section)) {
            busy?.let { item { Column(Modifier.padding(top = Spacing.md)) { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }
            item {
                SectionHeader("Biztonsági mentés")
                Text("A mentés egy ZIP-fájl az összes adattal és az eredeti blokk-képekkel. Te döntöd el, hová kerül; az alkalmazás semmit nem tölt fel.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    NavRow(Icons.Outlined.Save, "Mentés készítése", "ZIP: adatok + eredeti fájlok") { createBackup.launch("kiadaskoveto-mentes-${stamp()}.zip") }
                    NavRow(Icons.Outlined.Restore, "Visszaállítás mentésből", "A jelenlegi adatok lecserélődnek") { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) }
                }
            }
            item {
                val s = settings ?: return@item
                SectionHeader("Automatikus mentés")
                AppCard(Modifier.fillMaxWidth()) {
                    SwitchRow("Automatikus helyi mentés", "Időzített mentés a kiválasztott mappába vagy az alkalmazás tárhelyére.", s.autoBackup) { on ->
                        scope.launch { c.settings.setAutoBackup(on); WorkScheduler.scheduleAutoBackup(context, on, s.autoBackupIntervalDays) }
                    }
                    Text("Gyakoriság", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(1 to "Naponta", 7 to "Hetente", 30 to "Havonta").forEach { (d, l) ->
                            FilterChip(selected = s.autoBackupIntervalDays == d, onClick = {
                                scope.launch { c.settings.setAutoBackupIntervalDays(d); WorkScheduler.scheduleAutoBackup(context, s.autoBackup, d) }
                            }, label = { Text(l) })
                        }
                    }
                    ListRow("Mentési hely", subtitle = s.autoBackupFolder?.let { Uri.parse(it).lastPathSegment ?: it }
                        ?: "Alkalmazás belső tárhelye (az utolsó 5 mentés). Telefoncsere esetén válassz külső mappát.",
                        leading = { androidx.compose.material3.Icon(Icons.Outlined.FolderOpen, null) }, onClick = { pickFolder.launch(null) })
                    if (s.autoBackupFolder != null) OutlinedButton(onClick = { scope.launch { c.settings.setAutoBackupFolder(null) } }) { Text("Belső tárhely használata") }
                    if (s.lastBackupAt > 0) Text("Utolsó mentés: ${Fmt.dateTime(java.time.Instant.ofEpochMilli(s.lastBackupAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), true)} · ${s.lastBackupResult.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { job("Mentés…") { c.backup.autoBackup(s.autoBackupFolder).also { c.settings.setLastBackup(System.currentTimeMillis(), it) } } },
                        modifier = Modifier.padding(top = Spacing.sm)) { Text("Mentés most") }
                }
            }
            item {
                SectionHeader("Exportálás")
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    NavRow(Icons.Outlined.TableChart, "CSV (táblázat)", "Minden jóváhagyott tétel, Excelben megnyitható") { exportCsv.launch("kiadaskoveto-tetelek-${stamp()}.csv") }
                    NavRow(Icons.Outlined.DataObject, "JSON", "Teljes adatexport más programokhoz") { exportJson.launch("kiadaskoveto-adatok-${stamp()}.json") }
                }
                AppCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    Text("Havi PDF jelentés", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        (0..2).map { YearMonth.now().minusMonths(it.toLong()) }.forEach { ym ->
                            FilterChip(selected = reportMonth == ym, onClick = { reportMonth = ym }, label = { Text(Fmt.monthTitle(ym)) })
                        }
                    }
                    Button(onClick = { exportPdf.launch("kiadaskoveto-jelentes-$reportMonth.pdf") }, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp)) {
                        androidx.compose.material3.Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.height(0.dp)); Text("  PDF készítése")
                    }
                }
            }
        }
    }
    restoreCandidate?.let { (uri, data) ->
        ConfirmDialog(
            "Visszaállítás",
            "A mentés (${Fmt.dateTime(java.time.Instant.ofEpochMilli(data.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), true)}) " +
                "${data.receipts.size} blokkot, ${data.items.size} tételt és ${data.products.size} terméket tartalmaz. A jelenlegi adatok lecserélődnek; " +
                "előtte automatikus biztonsági másolat készül a jelenlegi állapotról.",
            "Visszaállítás",
            onConfirm = { job("Visszaállítás…") { val s = c.backup.restore(uri); "Visszaállítva: ${s.receipts} blokk, ${s.files} fájl" } },
            onDismiss = { restoreCandidate = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val model by produceState<InstalledModel?>(null, refresh) { value = withContext(Dispatchers.IO) { c.localModels.installed() } }
    val compat = remember(model) { c.localModels.compatibility(model) }
    val settings by c.settings.settings.collectAsState(initial = null)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            progress = 0f
            runCatching { c.localModels.import(uri, c.storage.displayName(uri) ?: "modell.gguf") { p -> progress = p } }
                .onSuccess { refresh++; snackbar.showSnackbar("Modell importálva: ${it.info.name ?: it.file.name}") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Az importálás nem sikerült.") }
            progress = null
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Helyi AI (opcionális)") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.section)) {
            item {
                Text("A KiadásKövető enélkül is teljes értékűen működik: a kategorizálást helyi szótár, szabályok, a te javításaid és hibatűrő szóegyeztetés végzi. " +
                    "Egy helyi nyelvi modell legfeljebb a bizonytalan terméknevek értelmezésében segíthetne; összegekhez és számításokhoz soha nem nyúl.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md))
                Spacer(Modifier.height(Spacing.md))
                if (!c.localModels.runtimeBundled) Notice("Ebben a verzióban nincs beépített LLM-futtatómotor (llama.cpp). Modellt importálhatsz és ellenőrizheted a kompatibilitását, de a futtatás egy későbbi verzióban érhető el.")
            }
            item {
                SectionHeader("Készülék")
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Memória: ${Fmt.bytes(compat.totalRamBytes)} (szabad: ${Fmt.bytes(compat.availableRamBytes)})", style = MaterialTheme.typography.bodyMedium)
                    Text("Architektúra: ${if (compat.is64Bit) "64 bites" else "32 bites (nem támogatott)"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Ajánlott: 1–3 milliárd paraméteres, Q4 kvantált, többnyelvű GGUF modell (kb. 0,7–2 GB fájl, legalább 4 GB RAM).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
            item {
                SectionHeader("Modell")
                AppCard(Modifier.fillMaxWidth()) {
                    val m = model
                    if (m == null) Text("Nincs importált modell.", style = MaterialTheme.typography.bodyMedium)
                    else {
                        Text(m.info.name ?: m.file.name, style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(m.info.architecture, m.info.sizeLabel, m.info.parameterCount?.let { "${it / 1_000_000} M paraméter" }, m.info.quantization,
                            m.info.contextLength?.let { "$it token kontextus" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text("Fájlméret: ${Fmt.bytes(m.sizeBytes)} · becsült memóriaigény: ${Fmt.bytes(compat.requiredRamBytes)}", style = MaterialTheme.typography.bodySmall)
                        Text(if (compat.fits) "A készülék memóriája elegendőnek tűnik." else "A modell valószínűleg túl nagy ehhez a készülékhez.",
                            color = if (compat.fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
                        Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = progress == null) { Text(if (m == null) "GGUF modell importálása" else "Csere") }
                        if (m != null) OutlinedButton(onClick = { c.localModels.delete(); refresh++ }) { Text("Törlés") }
                    }
                    settings?.let { s ->
                        SwitchRow("Használat bizonytalan tételeknél", if (c.localModels.runtimeBundled) null else "Futtatómotor nélkül nem kapcsolható be.",
                            s.localAiEnabled && c.localModels.runtimeBundled, enabled = c.localModels.runtimeBundled && m != null) { on -> scope.launch { c.settings.setLocalAiEnabled(on) } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Adatvédelem") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            val sections = listOf(
                "Hol vannak az adataid?" to "Minden blokk, tétel, termék, kategória, keret és beállítás kizárólag ezen a telefonon, az alkalmazás privát tárhelyén van. Az eredeti képek és PDF-ek is itt maradnak.",
                "Internet" to "Az alkalmazásnak nincs internet-hozzáférési engedélye. A szövegfelismerés, a blokkértelmezés, a kategorizálás és a kimutatások a telefonon futnak.",
                "Fiók, felhő, analitika" to "Nincs regisztráció, nincs felhőszinkronizálás, nincs reklám, nincs használati statisztika vagy hibajelentés-küldés.",
                "Rendszermentés" to "Az Android automatikus felhőmentése ki van kapcsolva ehhez az alkalmazáshoz, hogy pénzügyi adataid ne kerüljenek külső szolgáltatóhoz. Mentést te készíthetsz a Mentés és exportálás menüben, és te döntöd el, hová kerül.",
                "Engedélyek" to "Kamera: blokk és vonalkód fényképezéséhez. Értesítések: költségkeret-figyelmeztetésekhez. Biometria: az opcionális alkalmazászárhoz.",
                "Exportálás és megosztás" to "Adat csak akkor hagyja el az alkalmazást, ha te exportálsz vagy mentést készítesz egy általad választott helyre.",
                "Törlés" to "A Beállításokban egy lépésben törölheted az összes adatot. Az alkalmazás eltávolítása is minden adatot töröl.",
            )
            sections.forEach { (title, text) ->
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Névjegy") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.gutter)) {
            item {
                Text("KiadásKövető", style = MaterialTheme.typography.headlineSmall)
                Text("Verzió ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Offline személyes kiadáskövető magyar bolti blokkokhoz.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.sm))
                SectionHeader("Nyílt forrású összetevők")
            }
            val licenses = listOf(
                "Tesseract OCR 5" to "Apache License 2.0",
                "Tesseract4Android" to "Apache License 2.0",
                "tessdata_best (hun), tessdata_fast (eng) nyelvi modellek" to "Apache License 2.0",
                "Leptonica" to "BSD 2-Clause License",
                "libjpeg, libpng" to "IJG License, libpng License",
                "ZXing (vonalkód)" to "Apache License 2.0",
                "Android Jetpack (Compose, Room, CameraX, WorkManager, DataStore, Biometric)" to "Apache License 2.0",
                "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
                "Material ikonok" to "Apache License 2.0",
            )
            licenses.forEach { (n, l) -> item { ListRow(n, subtitle = l, leading = { androidx.compose.material3.Icon(Icons.Outlined.Description, null) }) } }
        }
    }
}

