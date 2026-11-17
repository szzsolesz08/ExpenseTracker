package hu.kiadaskoveto.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import hu.kiadaskoveto.AppContainer
import hu.kiadaskoveto.SharedInput
import hu.kiadaskoveto.data.settings.AppSettings
import hu.kiadaskoveto.feature.analytics.StatsScreen
import hu.kiadaskoveto.feature.budget.BudgetsScreen
import hu.kiadaskoveto.feature.dashboard.DashboardScreen
import hu.kiadaskoveto.feature.onboarding.OnboardingScreen
import hu.kiadaskoveto.feature.products.BarcodeScreen
import hu.kiadaskoveto.feature.products.ProductDetailScreen
import hu.kiadaskoveto.feature.products.ProductsScreen
import hu.kiadaskoveto.feature.receipts.DocumentViewerScreen
import hu.kiadaskoveto.feature.receipts.ReceiptDetailScreen
import hu.kiadaskoveto.feature.receipts.ReceiptsScreen
import hu.kiadaskoveto.feature.receipts.ReviewScreen
import hu.kiadaskoveto.feature.scanner.CameraScreen
import hu.kiadaskoveto.feature.scanner.PageReviewScreen
import hu.kiadaskoveto.feature.scanner.PdfPagesScreen
import hu.kiadaskoveto.feature.scanner.ProcessingScreen
import hu.kiadaskoveto.feature.settings.AboutScreen
import hu.kiadaskoveto.feature.settings.BackupScreen
import hu.kiadaskoveto.feature.settings.CategoriesScreen
import hu.kiadaskoveto.feature.settings.LocalAiScreen
import hu.kiadaskoveto.feature.settings.PrivacyScreen
import hu.kiadaskoveto.feature.settings.RulesScreen
import hu.kiadaskoveto.feature.settings.SettingsScreen
import hu.kiadaskoveto.feature.settings.StoresScreen
import hu.kiadaskoveto.ui.theme.MaxContentWidth
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDateTime

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("Snackbar not provided") }

private data class TopDestination(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val topDestinations = listOf(
    TopDestination(DashboardRoute, "Áttekintés", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard),
    TopDestination(ReceiptsRoute, "Blokkok", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
    TopDestination(StatsRoute, "Statisztika", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    TopDestination(ProductsRoute, "Termékek", Icons.Outlined.Inventory2, Icons.Filled.Inventory2),
    TopDestination(SettingsRoute, "Beállítások", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun KiadasRoot(
    settings: AppSettings?,
    locked: Boolean,
    onUnlockRequest: () -> Unit,
    openRequest: MutableStateFlow<String?>,
    sharedInput: MutableStateFlow<SharedInput?>,
) {
    when {
        settings == null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        locked -> LockScreen(onUnlockRequest)
        !settings.onboardingDone -> {
            val c = LocalAppContainer.current
            val scope = rememberCoroutineScope()
            OnboardingScreen(onFinish = { scope.launch { c.settings.setOnboardingDone(true) } })
        }
        else -> MainNavigation(openRequest, sharedInput)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(Spacing.lg))
            Text("A KiadásKövető zárolva van", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.sm))
            Text("Az adataid csak ezen a telefonon vannak. Feloldáshoz használd az ujjlenyomatodat, arcodat vagy a képernyőzárat.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xl))
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Feloldás") }
        }
    }
}

/** Navigates to a top-level destination keeping one copy of each in the back stack. */
fun NavHostController.navigateTop(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainNavigation(openRequest: MutableStateFlow<String?>, sharedInput: MutableStateFlow<SharedInput?>) {
    val c = LocalAppContainer.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val isTop = dest != null && topDestinations.any { dest.hasRoute(it.route::class) }
    val showFab = dest != null && (dest.hasRoute(DashboardRoute::class) || dest.hasRoute(ReceiptsRoute::class))
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var showAddSheet by remember { mutableStateOf(false) }

    fun showError(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { c.importHelper.addImages(uris) }
                .onSuccess { nav.navigate(PageReviewRoute) }
                .onFailure { showError(it.message ?: "A kép importálása nem sikerült.") }
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { c.importHelper.preparePdf(uri) }
                .onSuccess { (file, count) -> nav.navigate(PdfPagesRoute(file.absolutePath, count, c.importHelper.displayName(uri))) }
                .onFailure { showError(it.message ?: "A PDF importálása nem sikerült.") }
        }
    }

    // Owned by the activity that received the share, so a second activity instance cannot consume it.
    // One long-lived collector: clearing the value inside it cannot cancel the import that is in progress
    // (a LaunchedEffect keyed on the value would be cancelled by its own reset).
    LaunchedEffect(sharedInput) {
        sharedInput.filterNotNull().collect { input ->
            sharedInput.value = null
            c.importSession.reset()
            if (input.mimeType == "application/pdf") {
                try {
                    val (file, count) = c.importHelper.preparePdf(input.uris.first())
                    nav.navigate(PdfPagesRoute(file.absolutePath, count, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e.message ?: "A PDF importálása nem sikerült.")
                }
            } else {
                try {
                    c.importHelper.addImages(input.uris)
                    nav.navigate(PageReviewRoute)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e.message ?: "A kép importálása nem sikerült.")
                }
            }
        }
    }
    val open by openRequest.collectAsState()
    LaunchedEffect(open) {
        if (open == "budgets") { nav.navigate(BudgetsRoute); openRequest.value = null }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Row(Modifier.fillMaxSize()) {
            if (wide && isTop) {
                NavigationRail(header = {
                    if (showFab) ExtendedFloatingActionButton(onClick = { showAddSheet = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Blokk hozzáadása") })
                }) {
                    Spacer(Modifier.height(Spacing.lg))
                    topDestinations.forEach { d ->
                        val selected = dest.hasRoute(d.route::class)
                        NavigationRailItem(selected = selected, onClick = { nav.navigateTop(d.route) },
                            icon = { Icon(if (selected) d.selectedIcon else d.icon, contentDescription = null) }, label = { Text(d.label) })
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                floatingActionButton = {
                    if (showFab && !wide) {
                        ExtendedFloatingActionButton(
                            onClick = { showAddSheet = true },
                            modifier = Modifier.testTag("fab:add"),
                            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            text = { Text("Blokk hozzáadása") },
                        )
                    }
                },
                bottomBar = {
                    if (isTop && !wide) {
                        NavigationBar {
                            topDestinations.forEach { d ->
                                val selected = dest.hasRoute(d.route::class)
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav:${d.label}"),
                                    selected = selected,
                                    onClick = { nav.navigateTop(d.route) },
                                    icon = { Icon(if (selected) d.selectedIcon else d.icon, contentDescription = null) },
                                    label = { Text(d.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // On tablets, reading screens keep a comfortable measure; camera and image screens use the full width.
                val fullBleed = dest?.let { d ->
                    d.hasRoute(CameraRoute::class) || d.hasRoute(BarcodeRoute::class) ||
                        d.hasRoute(DocumentViewerRoute::class) || d.hasRoute(PageReviewRoute::class)
                } == true
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                NavHost(
                    navController = nav,
                    startDestination = DashboardRoute,
                    modifier = if (fullBleed) Modifier.fillMaxSize() else Modifier.widthIn(max = MaxContentWidth).fillMaxSize(),
                    enterTransition = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260), initialOffset = { it / 12 }) },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260), targetOffset = { it / 12 }) },
                ) {
                    composable<DashboardRoute> {
                        DashboardScreen(
                            onAddReceipt = { showAddSheet = true },
                            onOpenReceipt = { nav.navigate(ReceiptDetailRoute(it)) },
                            onOpenReview = { nav.navigate(ReviewRoute(it)) },
                            onOpenReceipts = { nav.navigateTop(ReceiptsRoute) },
                            onOpenStats = { nav.navigateTop(StatsRoute) },
                            onOpenProduct = { nav.navigate(ProductDetailRoute(it)) },
                            onOpenBudgets = { nav.navigate(BudgetsRoute) },
                        )
                    }
                    composable<ReceiptsRoute> {
                        ReceiptsScreen(
                            onOpenReceipt = { id, draft -> nav.navigate(if (draft) ReviewRoute(id) else ReceiptDetailRoute(id)) },
                            onAddReceipt = { showAddSheet = true },
                        )
                    }
                    composable<StatsRoute> { StatsScreen(onOpenProduct = { nav.navigate(ProductDetailRoute(it)) }) }
                    composable<ProductsRoute> {
                        ProductsScreen(onOpenProduct = { nav.navigate(ProductDetailRoute(it)) }, onScanBarcode = { nav.navigate(BarcodeRoute()) })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            onOpenBudgets = { nav.navigate(BudgetsRoute) },
                            onOpenCategories = { nav.navigate(CategoriesRoute) },
                            onOpenRules = { nav.navigate(RulesRoute) },
                            onOpenStores = { nav.navigate(StoresRoute) },
                            onOpenBackup = { nav.navigate(BackupRoute) },
                            onOpenLocalAi = { nav.navigate(LocalAiRoute) },
                            onOpenPrivacy = { nav.navigate(PrivacyRoute) },
                            onOpenAbout = { nav.navigate(AboutRoute) },
                        )
                    }
                    composable<CameraRoute> {
                        CameraScreen(onBack = { nav.popBackStack() }, onDone = { nav.navigate(PageReviewRoute) { popUpTo<CameraRoute> { inclusive = true } } })
                    }
                    composable<PdfPagesRoute> { entry ->
                        val r = entry.toRoute<PdfPagesRoute>()
                        PdfPagesScreen(r.path, r.pageCount, r.name, onBack = { nav.popBackStack() },
                            onDone = { nav.navigate(PageReviewRoute) { popUpTo<PdfPagesRoute> { inclusive = true } } })
                    }
                    composable<PageReviewRoute> {
                        PageReviewScreen(
                            onBack = { nav.popBackStack() },
                            onAddPhoto = { nav.navigate(CameraRoute) },
                            onAddImage = { pickImages.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                            onProcess = { nav.navigate(ProcessingRoute) { popUpTo<PageReviewRoute> { inclusive = true } } },
                        )
                    }
                    composable<ProcessingRoute> {
                        ProcessingScreen(
                            onDraftReady = { id -> nav.navigate(ReviewRoute(id, fresh = true)) { popUpTo<ProcessingRoute> { inclusive = true } } },
                            onOpenReceipt = { id -> nav.navigate(ReceiptDetailRoute(id)) { popUpTo<ProcessingRoute> { inclusive = true } } },
                            onClose = { nav.popBackStack() },
                        )
                    }
                    composable<ReviewRoute> { entry ->
                        val r = entry.toRoute<ReviewRoute>()
                        ReviewScreen(
                            receiptId = r.receiptId,
                            fresh = r.fresh,
                            onBack = { nav.popBackStack() },
                            onSaved = { id ->
                                nav.navigate(ReceiptDetailRoute(id)) { popUpTo<ReviewRoute> { inclusive = true } }
                                scope.launch { snackbar.showSnackbar("Blokk elmentve") }
                            },
                            onOpenDocument = { nav.navigate(DocumentViewerRoute(r.receiptId)) },
                            onOpenDuplicate = { nav.navigate(ReceiptDetailRoute(it)) },
                            onDeleted = {
                                // Pop only while this screen is still current (the delete completes asynchronously).
                                if (nav.currentDestination?.hasRoute(ReviewRoute::class) == true) nav.popBackStack()
                                scope.launch { snackbar.showSnackbar("Blokk törölve") }
                            },
                        )
                    }
                    composable<ReceiptDetailRoute> { entry ->
                        val r = entry.toRoute<ReceiptDetailRoute>()
                        ReceiptDetailScreen(
                            receiptId = r.receiptId,
                            onBack = { nav.popBackStack() },
                            onEdit = { nav.navigate(ReviewRoute(r.receiptId)) },
                            onOpenDocument = { nav.navigate(DocumentViewerRoute(r.receiptId)) },
                            onOpenProduct = { nav.navigate(ProductDetailRoute(it)) },
                            onDeleted = {
                                if (nav.currentDestination?.hasRoute(ReceiptDetailRoute::class) == true) nav.popBackStack()
                                scope.launch { snackbar.showSnackbar("Blokk törölve") }
                            },
                        )
                    }
                    composable<DocumentViewerRoute> { entry -> DocumentViewerScreen(entry.toRoute<DocumentViewerRoute>().receiptId, onBack = { nav.popBackStack() }) }
                    composable<ProductDetailRoute> { entry ->
                        val r = entry.toRoute<ProductDetailRoute>()
                        ProductDetailScreen(
                            productId = r.productId,
                            onBack = { nav.popBackStack() },
                            onOpenReceipt = { nav.navigate(ReceiptDetailRoute(it)) },
                            onOpenProduct = { nav.navigate(ProductDetailRoute(it)) { popUpTo<ProductDetailRoute> { inclusive = true } } },
                            onScanBarcode = { nav.navigate(BarcodeRoute(r.productId)) },
                        )
                    }
                    composable<BarcodeRoute> { entry ->
                        val r = entry.toRoute<BarcodeRoute>()
                        BarcodeScreen(
                            assignToProductId = r.assignToProductId.takeIf { it > 0 },
                            onBack = { nav.popBackStack() },
                            onOpenProduct = { nav.navigate(ProductDetailRoute(it)) { popUpTo<BarcodeRoute> { inclusive = true } } },
                        )
                    }
                    composable<BudgetsRoute> { BudgetsScreen(onBack = { nav.popBackStack() }) }
                    composable<CategoriesRoute> { CategoriesScreen(onBack = { nav.popBackStack() }, onOpenRules = { nav.navigate(RulesRoute) }) }
                    composable<RulesRoute> { RulesScreen(onBack = { nav.popBackStack() }) }
                    composable<StoresRoute> { StoresScreen(onBack = { nav.popBackStack() }) }
                    composable<BackupRoute> { BackupScreen(onBack = { nav.popBackStack() }) }
                    composable<LocalAiRoute> { LocalAiScreen(onBack = { nav.popBackStack() }) }
                    composable<PrivacyRoute> { PrivacyScreen(onBack = { nav.popBackStack() }) }
                    composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }
                }
                }
            }
        }

        if (showAddSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
                AddReceiptSheet(
                    onCamera = { showAddSheet = false; c.importSession.reset(); nav.navigate(CameraRoute) },
                    onImage = { showAddSheet = false; c.importSession.reset(); pickImages.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                    onPdf = { showAddSheet = false; c.importSession.reset(); pickPdf.launch(arrayOf("application/pdf")) },
                    onManual = {
                        showAddSheet = false
                        scope.launch { nav.navigate(ReviewRoute(c.receipts.createManual(null, LocalDateTime.now()))) }
                    },
                )
            }
        }
    }
}

@Composable
private fun AddReceiptSheet(onCamera: () -> Unit, onImage: () -> Unit, onPdf: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
        Text("Blokk hozzáadása", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
        SheetOption(Icons.Outlined.CameraAlt, "Fényképezés", "Hosszú blokkot több képben is lefényképezhetsz.", onCamera)
        SheetOption(Icons.Outlined.PhotoLibrary, "Kép importálása", "JPG, PNG vagy WebP — egy blokk több képe is.", onImage)
        SheetOption(Icons.Outlined.PictureAsPdf, "PDF importálása", "Elektronikus blokk vagy beszkennelt PDF; választható oldalak.", onPdf)
        SheetOption(Icons.Outlined.EditNote, "Kézi rögzítés", "Blokk nélkül, tételek kézi megadásával.", onManual)
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    hu.kiadaskoveto.ui.components.ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}
