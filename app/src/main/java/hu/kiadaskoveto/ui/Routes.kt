package hu.kiadaskoveto.ui

import kotlinx.serialization.Serializable

// Top-level destinations
@Serializable object DashboardRoute
@Serializable object ReceiptsRoute
@Serializable object StatsRoute
@Serializable object ProductsRoute
@Serializable object SettingsRoute

// Flows and details
@Serializable object OnboardingRoute
@Serializable object CameraRoute
@Serializable data class PdfPagesRoute(val path: String, val pageCount: Int, val name: String? = null)
@Serializable object PageReviewRoute
@Serializable object ProcessingRoute
@Serializable data class ReviewRoute(val receiptId: Long, val fresh: Boolean = false)
@Serializable data class ReceiptDetailRoute(val receiptId: Long)
@Serializable data class DocumentViewerRoute(val receiptId: Long)
@Serializable data class ProductDetailRoute(val productId: Long)
@Serializable data class BarcodeRoute(val assignToProductId: Long = 0)
@Serializable object BudgetsRoute
@Serializable object CategoriesRoute
@Serializable object RulesRoute
@Serializable object StoresRoute
@Serializable object BackupRoute
@Serializable object LocalAiRoute
@Serializable object PrivacyRoute
@Serializable object AboutRoute
