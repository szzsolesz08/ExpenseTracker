package hu.kiadaskoveto.feature.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.data.importer.ImportOutcome
import hu.kiadaskoveto.data.importer.ImportProgress
import hu.kiadaskoveto.data.importer.ImportStep
import hu.kiadaskoveto.ocr.pdf.PdfPageRenderer
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPagesScreen(path: String, pageCount: Int, name: String?, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppContainer.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateMapOf<Int, Boolean>().apply { (0 until minOf(pageCount, 10)).forEach { put(it, true) } } }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            runCatching {
                PdfPageRenderer.open(File(path)).use { pdf -> for (i in 0 until pageCount) thumbs[i] = pdf.thumbnail(i, 300) }
            }
        }
    }
    val count = selected.count { it.value }
    Scaffold(
        topBar = { TopAppBar(title = { Text(name ?: "PDF oldalai") }, navigationIcon = { BackIcon(onBack) }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { val all = count < pageCount; (0 until pageCount).forEach { selected[it] = all } }) {
                        Text(if (count < pageCount) "Összes kijelölése" else "Kijelölés törlése")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = count > 0 && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { c.importHelper.addPdfPages(File(path), name, selected.filter { it.value }.keys.toList()) }
                                    .onSuccess { onDone() }
                                    .onFailure { busy = false; snackbar.showSnackbar(it.message ?: "Az oldalak feldolgozása nem sikerült.") }
                            }
                        },
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Tovább ($count oldal)") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Válaszd ki a blokkot tartalmazó oldalakat. Egy blokk több oldalon is lehet.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm))
            if (count > 10) Notice("Sok oldalt jelöltél ki; a feldolgozás hosszabb ideig tarthat.", Modifier.padding(horizontal = Spacing.gutter))
            LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items((0 until pageCount).toList()) { i ->
                    val isSel = selected[i] == true
                    Surface(
                        onClick = { selected[i] = !isSel },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.7f), contentAlignment = Alignment.Center) {
                                thumbs[i]?.let { Image(it.asImageBitmap(), contentDescription = "${i + 1}. oldal", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                    ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                                Checkbox(checked = isSel, onCheckedChange = { selected[i] = it })
                                Text("${i + 1}. oldal", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class StepState { PENDING, ACTIVE, DONE, FAILED }

class ProcessingViewModel(private val c: AppContainer) : ViewModel() {
    val progress = MutableStateFlow(ImportProgress(ImportStep.IMPORTED, 0f))
    val outcome = MutableStateFlow<ImportOutcome?>(null)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = viewModelScope.launch {
            outcome.value = c.pipeline.run(c.importSession) { p -> progress.value = p }
        }
    }

    fun cancel() { c.pipeline.cancel() }
}

@Composable
fun ProcessingScreen(onDraftReady: (Long) -> Unit, onOpenReceipt: (Long) -> Unit, onClose: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: ProcessingViewModel = viewModel { ProcessingViewModel(c) }
    val progress by vm.progress.collectAsState()
    val outcome by vm.outcome.collectAsState()
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(outcome) { (outcome as? ImportOutcome.Draft)?.let { onDraftReady(it.receiptId) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Spacing.xl)) {
            Text("Blokk feldolgozása", style = MaterialTheme.typography.headlineSmall)
            Text("Minden lépés a telefonodon fut, internet nélkül.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xl))
            val o = outcome
            val currentIndex = progress.step.ordinal
            val overall = ((currentIndex + progress.fraction.coerceIn(0f, 1f)) / ImportStep.entries.size)
            LinearProgressIndicator(progress = { if (o != null) 1f else overall }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.xl))
            ImportStep.entries.forEach { step ->
                val state = when {
                    o is ImportOutcome.Failed || o is ImportOutcome.Cancelled -> when {
                        step.ordinal < currentIndex -> StepState.DONE
                        step.ordinal == currentIndex -> StepState.FAILED
                        else -> StepState.PENDING
                    }
                    step == ImportStep.REVIEW || step == ImportStep.SAVE -> if (step.ordinal < currentIndex || (o is ImportOutcome.Draft && step == ImportStep.REVIEW)) StepState.DONE else if (step.ordinal == currentIndex) StepState.ACTIVE else StepState.PENDING
                    step.ordinal < currentIndex -> StepState.DONE
                    step.ordinal == currentIndex -> StepState.ACTIVE
                    else -> StepState.PENDING
                }
                val active = step.ordinal == currentIndex && state == StepState.ACTIVE
                StepRow(step, state, if (active) progress.detail else null, if (active) progress.fraction.coerceIn(0f, 1f) else 0f)
            }
            Spacer(Modifier.weight(1f))
            when (o) {
                null -> OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Megszakítás") }
                is ImportOutcome.Failed -> {
                    Notice(o.message, isError = true)
                    Spacer(Modifier.height(Spacing.md))
                    if (o.receiptId != null) Button(onClick = { onDraftReady(o.receiptId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Kézi rögzítés ehhez a képhez") }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bezárás") }
                }
                is ImportOutcome.Cancelled -> {
                    Text("Megszakítottad a feldolgozást. Az eredeti képet megőriztük a Blokkok között, ahonnan törölheted vagy kézzel rögzítheted.",
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.md))
                    if (o.receiptId != null) OutlinedButton(onClick = { onOpenReceipt(o.receiptId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Megőrzött kép megnyitása") }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bezárás") }
                }
                is ImportOutcome.Draft -> Unit
            }
        }
    }
}

@Composable
private fun StepRow(step: ImportStep, state: StepState, detail: String?, fraction: Float) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.DONE -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Kész", tint = MaterialTheme.colorScheme.primary)
                // Determinate on purpose: it redraws only when the step reports progress. An indeterminate spinner
                // animates every frame for the whole OCR run, which competes with recognition for the CPU.
                StepState.ACTIVE -> CircularProgressIndicator(
                    progress = { fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                StepState.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Sikertelen", tint = MaterialTheme.colorScheme.error)
                StepState.PENDING -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "Várakozik", tint = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(step.label, style = MaterialTheme.typography.bodyLarge,
                color = if (state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
