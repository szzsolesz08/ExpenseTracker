package hu.kiadaskoveto.feature.products

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.ocr.barcode.BarcodeDecoder
import hu.kiadaskoveto.ocr.barcode.BarcodeResult
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.LocalSnackbar
import hu.kiadaskoveto.ui.components.BackIcon
import hu.kiadaskoveto.ui.components.ListRow
import hu.kiadaskoveto.ui.components.Notice
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Offline barcode scanning (ZXing). A product barcode is matched against products in the local
 * database only — there is no online lookup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(assignToProductId: Long?, onBack: () -> Unit, onOpenProduct: (Long) -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    var result by remember { mutableStateOf<BarcodeResult?>(null) }
    var manual by remember { mutableStateOf("") }
    val products by c.products.productsWithStats.collectAsState(initial = emptyList())
    val previewView = remember { PreviewView(context) }

    if (granted) DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val decoder = BarcodeDecoder()
        val job = scope.launch {
            runCatching {
                val provider = ProcessCameraProvider.awaitInstance(context)
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (result == null) {
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            decoder.decodeLuminance(bytes, plane.rowStride, image.width, image.height)?.let { r -> previewView.post { result = r } }
                        }
                    } finally { image.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { snackbar.showSnackbar("A kamera nem indítható: ${it.message}") }
        }
        onDispose {
            job.cancel()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (assignToProductId != null) "Vonalkód hozzárendelése" else "Vonalkód beolvasása") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                if (granted) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                else Text("Kamera-engedély szükséges a beolvasáshoz.", color = Color.White, modifier = Modifier.padding(Spacing.xl))
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(Spacing.gutter)) {
                Notice("A blokk alján lévő vonalkód vagy QR-kód a vásárlás azonosítója, nem tartalmazza a megvett termékeket. A termékek a blokk szövegéből származnak.")
                Spacer(Modifier.height(Spacing.md))
                val r = result
                if (r == null) {
                    Text("Tartsd a kamerát egy termék vonalkódja (EAN-13, EAN-8, UPC-A), Code 128 vagy QR-kód fölé.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(manual, { manual = it.filter { ch -> ch.isDigit() } }, label = { Text("Vagy írd be a kódot") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { result = BarcodeResult(manual, hu.kiadaskoveto.ocr.barcode.BarcodeType.EAN_13) }, enabled = manual.length >= 8,
                        modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp)) { Text("Keresés") }
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.lg)) {
                            Text(r.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.text, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                            if (r.type.isProductCode && !BarcodeDecoder.isValidGtin(r.text)) Text("Az ellenőrzőszám hibás — olvasd be újra.", color = MaterialTheme.colorScheme.error)
                            if (!r.type.isProductCode) Text("Ez valószínűleg nem termékkód (pl. blokk-azonosító).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    if (assignToProductId != null) {
                        Button(onClick = { scope.launch { c.products.setBarcode(assignToProductId, r.text); onOpenProduct(assignToProductId) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Hozzárendelés a termékhez")
                        }
                    } else {
                        val match = products.firstOrNull { it.product.barcode == r.text }
                        if (match != null) {
                            Button(onClick = { onOpenProduct(match.product.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Megnyitás: ${match.product.name}") }
                        } else {
                            Text("Ehhez a kódhoz még nincs termék rendelve. Válaszd ki, melyik termékhez tartozik:", style = MaterialTheme.typography.bodyMedium)
                            var q by remember { mutableStateOf("") }
                            OutlinedTextField(q, { q = it }, label = { Text("Termék keresése") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm))
                            val key = HungarianText.matchKey(q)
                            products.filter { key.isBlank() || HungarianText.matchKey(it.product.name).contains(key) }.take(15).forEach { p ->
                                ListRow(p.product.name, subtitle = "${p.purchaseCount}× vásárolva", onClick = {
                                    scope.launch { c.products.setBarcode(p.product.id, r.text); onOpenProduct(p.product.id) }
                                })
                            }
                        }
                    }
                    OutlinedButton(onClick = { result = null }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp)) { Text("Új beolvasás") }
                }
            }
        }
    }
}
