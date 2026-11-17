package hu.kiadaskoveto.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * KiadásKövető design system
 * - Restrained colour strategy: green-grey tinted neutrals (thermal-paper whites in light mode,
 *   deep green-black in dark mode) plus one accent, "forint green".
 * - Amber (tertiary) is reserved for "needs review"; error red for over-budget/destructive.
 *   Status colour is always paired with an icon and a label.
 * - Roboto through the Material type scale; tabular figures only for aligned number columns.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E6B50), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFDF), onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4E6359), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E8E0), onSecondaryContainer = Color(0xFF0B1F17),
    tertiary = Color(0xFF855400), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB4), onTertiaryContainer = Color(0xFF2A1800),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F7F5), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6F7F5), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DF), onSurfaceVariant = Color(0xFF404944),
    surfaceTint = Color(0xFF0E6B50),
    surfaceBright = Color(0xFFF6F7F5), surfaceDim = Color(0xFFD6DBD7),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F3F0),
    surfaceContainer = Color(0xFFEAEEEB), surfaceContainerHigh = Color(0xFFE4E9E5),
    surfaceContainerHighest = Color(0xFFDEE4DF),
    outline = Color(0xFF707973), outlineVariant = Color(0xFFC0C9C3),
    inverseSurface = Color(0xFF2C322F), inverseOnSurface = Color(0xFFEDF2EE), inversePrimary = Color(0xFF7FD8B4),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8B4), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C), onPrimaryContainer = Color(0xFF9CF5CF),
    secondary = Color(0xFFB3CCBF), onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41), onSecondaryContainer = Color(0xFFCFE8DA),
    tertiary = Color(0xFFF6BC6A), onTertiary = Color(0xFF482A00),
    tertiaryContainer = Color(0xFF653E00), onTertiaryContainer = Color(0xFFFFDDB4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1412), onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0F1412), onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404944), onSurfaceVariant = Color(0xFFC0C9C3),
    surfaceTint = Color(0xFF7FD8B4),
    surfaceBright = Color(0xFF353B38), surfaceDim = Color(0xFF0F1412),
    surfaceContainerLowest = Color(0xFF0A0F0D), surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF8A938D), outlineVariant = Color(0xFF404944),
    inverseSurface = Color(0xFFDEE4DF), inverseOnSurface = Color(0xFF2C322F), inversePrimary = Color(0xFF0E6B50),
    scrim = Color.Black,
)

/** Colours used by charts and status cues (validated with the dataviz palette checks). */
@Immutable
data class ExtendedColors(
    val chartAccent: Color,
    val chartMuted: Color,
    val chartGrid: Color,
    val chartSurface: Color,
    val positive: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val paper: Color,
    val onPaper: Color,
    val paperMuted: Color,
)

private val LightExtended = ExtendedColors(
    chartAccent = Color(0xFF0E7A5A), chartMuted = Color(0xFFC9D1CC), chartGrid = Color(0xFFE3E8E4), chartSurface = Color.White,
    positive = Color(0xFF006D3F), warning = Color(0xFF855400), warningContainer = Color(0xFFFFDDB4), onWarningContainer = Color(0xFF2A1800),
    paper = Color(0xFFFFFFFF), onPaper = Color(0xFF151A17), paperMuted = Color(0xFF5B635E),
)

private val DarkExtended = ExtendedColors(
    chartAccent = Color(0xFF33A77C), chartMuted = Color(0xFF3A443F), chartGrid = Color(0xFF2A322E), chartSurface = Color(0xFF1A211E),
    positive = Color(0xFF6FDBA6), warning = Color(0xFFF6BC6A), warningContainer = Color(0xFF653E00), onWarningContainer = Color(0xFFFFDDB4),
    paper = Color(0xFF1E2522), onPaper = Color(0xFFE6ECE8), paperMuted = Color(0xFFA9B3AD),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

private val Base = Typography()

/** Material type scale with slightly tighter, heavier display/headline roles for numbers. */
val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1.2).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall,
)

/** Tabular figures for vertically aligned amounts (receipt lines, tables, axis ticks). */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

val Monospace = FontFamily.Monospace

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Content measure on tablets and foldables (Material medium-width breakpoint). */
val MaxContentWidth = 840.dp

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val section = 32.dp
    /** Horizontal screen gutter. */
    val gutter = 20.dp
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun KiadasTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppTheme {
    val extended: ExtendedColors @Composable get() = LocalExtendedColors.current
}
