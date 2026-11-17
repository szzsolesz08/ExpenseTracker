package hu.kiadaskoveto.feature.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.data.importer.ImportPage
import hu.kiadaskoveto.ocr.ImagePreprocessor
import hu.kiadaskoveto.ocr.image.BitmapOps
import hu.kiadaskoveto.ocr.image.PointF2
import hu.kiadaskoveto.ocr.image.Quad
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.EmptyState
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReviewScreen(onBack: () -> Unit, onAddPhoto: () -> Unit, onAddImage: () -> Unit, onProcess: () -> Unit) {
    val c = LocalAppContainer.current
    val pages by c.importSession.pages.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Oldalak ellenőrzése") }, navigationIcon = { BackIcon(onBack) }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        IconButton(onClick = onAddPhoto) { Icon(Icons.Outlined.AddAPhoto, contentDescription = "Újabb fénykép") }
                        IconButton(onClick = onAddImage) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Újabb kép importálása") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onProcess, enabled = pages.isNotEmpty(), modifier = Modifier.heightIn(min = 52.dp)) {
                            Text(if (pages.size > 1) "Feldolgozás (${pages.size} oldal)" else "Feldolgozás")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (pages.isEmpty()) {
            EmptyState(Icons.Outlined.AddAPhoto, "Nincs oldal", "Fényképezz le vagy importálj egy blokkot.", Modifier.padding(padding),
                primaryAction = "Fénykép készítése", onPrimary = onAddPhoto, secondaryAction = "Kép importálása", onSecondary = onAddImage)
            return@Scaffold
        }
        val pager = rememberPagerState { pages.size }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "${pager.currentPage + 1}. oldal / ${pages.size} · Húzd a sarkokat a blokk széleihez. Az eredeti fájl változatlan marad.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
            )
            HorizontalPager(pager, Modifier.weight(1f), key = { pages.getOrNull(it)?.id ?: it }) { i ->
                pages.getOrNull(i)?.let { PageEditor(it, onChange = { t -> c.importSession.updatePage(it.id, t) }) }
            }
            val current = pages.getOrNull(pager.currentPage)
            if (current != null) Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { c.importSession.updatePage(current.id) { it.copy(quarterTurns = (it.quarterTurns + 3) % 4, crop = null) } }) {
                    Icon(Icons.AutoMirrored.Outlined.RotateLeft, contentDescription = "Forgatás balra")
                }
                IconButton(onClick = { c.importSession.updatePage(current.id) { it.copy(quarterTurns = (it.quarterTurns + 1) % 4, crop = null) } }) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Forgatás jobbra")
                }
                IconButton(onClick = { c.importSession.updatePage(current.id) { it.copy(crop = Quad.full(1, 1)) } }) {
                    Icon(Icons.Outlined.CropFree, contentDescription = "Teljes kép használata")
                }
                IconButton(enabled = pager.currentPage > 0, onClick = { c.importSession.movePage(current.id, -1) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Előrébb sorolás")
                }
                IconButton(enabled = pager.currentPage < pages.lastIndex, onClick = { c.importSession.movePage(current.id, 1) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Hátrébb sorolás")
                }
                IconButton(onClick = { c.importSession.removePage(current.id) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Oldal eltávolítása", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Preview of one page with the rotation applied and a draggable 4-corner crop. */
@Composable
private fun PageEditor(page: ImportPage, onChange: ((ImportPage) -> ImportPage) -> Unit) {
    val bitmap by produceState<Bitmap?>(null, page.imageFile, page.quarterTurns) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapOps.rotate(BitmapOps.decode(page.imageFile, 1400), 90f * page.quarterTurns) }.getOrNull()
        }
    }
    val bmp = bitmap ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Kép betöltése…") }
    var suggested by remember(page.id, page.quarterTurns) { mutableStateOf<Quad?>(null) }
    LaunchedEffect(bmp) { if (page.crop == null) suggested = withContext(Dispatchers.Default) { runCatching { ImagePreprocessor.suggestCrop(bmp) }.getOrNull() } }
    val quad = page.crop ?: suggested ?: Quad(PointF2(0.04f, 0.03f), PointF2(0.96f, 0.03f), PointF2(0.96f, 0.97f), PointF2(0.04f, 0.97f))
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().padding(Spacing.md), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val scale = minOf(boxW / bmp.width, boxH / bmp.height)
        val imgW = bmp.width * scale; val imgH = bmp.height * scale
        val left = (boxW - imgW) / 2; val top = (boxH - imgH) / 2
        fun toScreen(p: PointF2) = Offset(left + p.x * imgW, top + p.y * imgH)
        fun toNorm(o: Offset) = PointF2(((o.x - left) / imgW).coerceIn(0f, 1f), ((o.y - top) / imgH).coerceIn(0f, 1f))
        var dragging by remember { mutableStateOf(-1) }
        var live by remember(quad) { mutableStateOf(quad) }

        Image(bmp.asImageBitmap(), contentDescription = "Blokk előnézet", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(page.id, page.quarterTurns, quad) {
                val touch = with(density) { 40.dp.toPx() }
                detectDragGestures(
                    onDragStart = { pos ->
                        val pts = live.points.map { toScreen(it) }
                        val (idx, dist) = pts.mapIndexed { i, p -> i to hypot(p.x - pos.x, p.y - pos.y) }.minBy { it.second }
                        dragging = if (dist <= touch) idx else -1
                    },
                    onDrag = { change, _ ->
                        if (dragging >= 0) {
                            change.consume()
                            val p = toNorm(change.position)
                            live = when (dragging) { 0 -> live.copy(tl = p); 1 -> live.copy(tr = p); 2 -> live.copy(br = p); else -> live.copy(bl = p) }
                        }
                    },
                    onDragEnd = { if (dragging >= 0) { val q = live; onChange { it.copy(crop = q) } }; dragging = -1 },
                    onDragCancel = { dragging = -1 },
                )
            }
        ) {
            val pts = live.points.map { toScreen(it) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, Color.Black.copy(alpha = 0.0f))
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = 13.dp.toPx(), center = p)
                drawCircle(accent, radius = if (i == dragging) 11.dp.toPx() else 9.dp.toPx(), center = p)
            }
        }
    }
}

