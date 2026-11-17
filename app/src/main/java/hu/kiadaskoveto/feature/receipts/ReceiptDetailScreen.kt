package hu.kiadaskoveto.feature.receipts

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.data.repo.ReceiptDetail
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.paymentLabel
import hu.kiadaskoveto.ocr.image.BitmapOps
import hu.kiadaskoveto.ocr.pdf.PdfPageRenderer
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.AppCard
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.CategoryBadge
import hu.kiadaskoveto.ui.components.ConfirmDialog
import hu.kiadaskoveto.ui.components.LoadingState
import hu.kiadaskoveto.ui.components.PerforatedEdgeShape
import hu.kiadaskoveto.ui.components.RankedBars
import hu.kiadaskoveto.ui.components.RankedRow
import hu.kiadaskoveto.ui.components.ReviewMarker
import hu.kiadaskoveto.ui.components.SectionHeader
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReceiptDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val detail: StateFlow<ReceiptDetail?> = c.receipts.observeDetail(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.receipts.delete(id); onDone() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(
    receiptId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: ReceiptDetailViewModel = viewModel(key = "detail$receiptId") { ReceiptDetailViewModel(c, receiptId) }
    val detail by vm.detail.collectAsState()
    val categories by vm.categories.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Blokk") }, navigationIcon = { BackIcon(onBack) }, actions = {
            if (detail?.documents?.isNotEmpty() == true) IconButton(onClick = onOpenDocument) { Icon(Icons.Outlined.Image, contentDescription = "Eredeti blokk") }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Szerkesztés") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Törlés") }
        })
    }) { padding ->
        val d = detail
        if (d == null) { LoadingState(modifier = Modifier.padding(padding)); return@Scaffold }
        val r = d.receipt
        val ext = AppTheme.extended
        val catById = categories.associateBy { it.id }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                // The receipt slip
                Column(
                    Modifier.widthIn(max = 460.dp).fillMaxWidth()
                        .shadow(2.dp, PerforatedEdgeShape())
                        .clip(PerforatedEdgeShape())
                        .background(ext.paper)
                        .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl, bottom = Spacing.xxl),
                ) {
                    Text(r.storeName ?: "Ismeretlen bolt", style = MaterialTheme.typography.titleLarge, color = ext.onPaper, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    r.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ext.paperMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    Text(if (r.dateKnown) Fmt.dateTime(r.purchasedAt, r.hasTime) else "Dátum nincs megadva", style = MaterialTheme.typography.bodyMedium,
                        color = ext.paperMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    DashedRule()
                    d.items.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(item.displayName, style = MaterialTheme.typography.bodyMedium, color = ext.onPaper, modifier = Modifier.weight(1f))
                                Text(Fmt.money(item.lineTotal), style = MaterialTheme.typography.bodyMedium.tabular, color = ext.onPaper)
                            }
                            if (item.quantity != null) Text("${item.quantity.format()} ${item.unit?.symbol ?: "db"}" + (item.unitPrice?.let { " × ${Fmt.money(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.tabular, color = ext.paperMuted)
                            if (!item.discount.isZero) Row {
                                Text(item.discountText ?: "Kedvezmény", style = MaterialTheme.typography.bodySmall, color = ext.positive, modifier = Modifier.weight(1f))
                                Text(Fmt.money(item.discount), style = MaterialTheme.typography.bodySmall.tabular, color = ext.positive)
                            }
                            if (item.needsReview) ReviewMarker(item.reviewNote?.substringBefore(";") ?: "Ellenőrizd", Modifier.padding(top = 2.dp))
                        }
                    }
                    DashedRule()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ÖSSZESEN", style = MaterialTheme.typography.titleMedium, color = ext.onPaper, modifier = Modifier.weight(1f))
                        Text(Fmt.money(r.total), style = MaterialTheme.typography.headlineSmall.tabular, color = ext.onPaper)
                    }
                    r.rounding?.takeIf { !it.isZero }?.let { Text("Kerekítés: ${Fmt.money(it)}", style = MaterialTheme.typography.bodySmall, color = ext.paperMuted) }
                    r.totalDifference?.takeIf { !it.isZero }?.let {
                        ReviewMarker("A tételek összege ${Fmt.money(it.abs)} összeggel eltér", Modifier.padding(top = Spacing.xs))
                    }
                    paymentLabel(r.paymentMethod)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ext.paperMuted, modifier = Modifier.padding(top = Spacing.xs)) }
                    val ids = listOfNotNull(r.receiptNumber?.let { "Nyugtaszám: $it" }, r.apCode?.let { "AP $it" }, r.taxNumber?.let { "Adószám: $it" })
                    if (ids.isNotEmpty()) Text(ids.joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = ext.paperMuted, modifier = Modifier.padding(top = Spacing.sm))
                }
            }
            val byCategory = d.items.filter { it.kind != ItemKind.DISCOUNT }.groupBy { it.categoryId }
                .map { (cid, items) -> catById[cid] to items.sumOf { it.finalPrice.minor } }.filter { it.second > 0 }.sortedByDescending { it.second }
            if (byCategory.size > 1) item {
                SectionHeader("Kategóriák ebben a blokkban", Modifier.widthIn(max = 460.dp))
                AppCard(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                    RankedBars(byCategory.map { (cat, v) -> RankedRow(cat?.name ?: "Nem besorolt", v, leading = { CategoryBadge(cat, 32.dp) }) })
                }
            }
            val linked = d.items.filter { it.productId != null }
            if (linked.isNotEmpty()) item {
                SectionHeader("Termékek", Modifier.widthIn(max = 460.dp))
                AppCard(Modifier.widthIn(max = 460.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    linked.forEach { item ->
                        hu.kiadaskoveto.ui.components.ListRow(item.displayName, subtitle = "Árelőzmények és vásárlások",
                            leading = { CategoryBadge(catById[item.categoryId], 32.dp) }, onClick = { onOpenProduct(item.productId!!) })
                    }
                }
            }
            r.notes?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.lg)) } }
            item { Spacer(Modifier.height(Spacing.section)) }
        }
    }
    if (confirmDelete) ConfirmDialog("Blokk törlése", "A blokk, a tételei és az eredeti fájlok véglegesen törlődnek a telefonról. Ez nem vonható vissza.", "Törlés",
        onConfirm = { vm.delete(onDeleted) }, onDismiss = { confirmDelete = false }, destructive = true)
}

@Composable
private fun DashedRule() {
    val color = AppTheme.extended.paperMuted.copy(alpha = 0.5f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(vertical = Spacing.md).height(1.dp)) {
        val dash = 6.dp.toPx(); val gap = 4.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(minOf(x + dash, size.width), 0f), strokeWidth = 1.dp.toPx()); x += dash + gap }
    }
}

/** Original receipt files (and processed versions) with pinch-to-zoom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(receiptId: Long, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val pages by produceState<List<Pair<String, Bitmap>>?>(null, receiptId) {
        value = withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, Bitmap>>()
            for (doc in c.receipts.documentEntities(receiptId)) {
                val f = c.storage.resolve(doc.filePath)
                if (!f.exists()) continue
                runCatching {
                    if (doc.mimeType == "application/pdf") {
                        PdfPageRenderer.open(f).use { pdf ->
                            val selected = doc.selectedPages?.split(',')?.mapNotNull { it.toIntOrNull() } ?: (0 until pdf.pageCount).toList()
                            selected.forEach { p -> out += "PDF ${p + 1}. oldal" to pdf.render(p, dpi = 160) }
                        }
                    } else out += "Eredeti kép ${doc.pageIndex + 1}" to BitmapOps.decode(f, 2400)
                }
            }
            val folder = c.receipts.documentEntities(receiptId).firstOrNull()?.filePath?.substringBeforeLast('/')
            if (folder != null) {
                c.storage.resolve(folder).listFiles { file: File -> file.name.startsWith("processed_") }?.sortedBy { it.name }?.forEach { p ->
                    runCatching { out += "Feldolgozott ${p.nameWithoutExtension.substringAfter('_')}" to BitmapOps.decode(p, 2400) }
                }
            }
            out
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Eredeti blokk") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        val list = pages
        when {
            list == null -> LoadingState(modifier = Modifier.padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Ehhez a blokkhoz nem tartozik eredeti fájl.") }
            else -> {
                val pager = rememberPagerState { list.size }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Text("${list[pager.currentPage].first} · ${pager.currentPage + 1}/${list.size}", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm))
                    HorizontalPager(pager, Modifier.weight(1f)) { i -> ZoomableImage(list[i].second) }
                }
            }
        }
    }
}

@Composable
fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    LaunchedEffect(bitmap) { scale = 1f; offset = Offset.Zero }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Blokk képe",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().transformable(state).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}

