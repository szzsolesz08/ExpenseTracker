package hu.kiadaskoveto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.CategoryVisuals
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Raised content surface: white paper on the tinted ground, soft offset shadow. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 2.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, elevation = elevation) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, elevation = elevation) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.xxl, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    aligned: Boolean = true,
) {
    Text(
        text = Fmt.money(money),
        modifier = modifier,
        style = if (aligned) style.tabular else style,
        color = color,
        maxLines = 1,
    )
}

/**
 * Change versus a previous period. For spending, up is bad: colour follows direction × meaning,
 * and the arrow icon + words carry the meaning too (never colour alone).
 */
@Composable
fun DeltaBadge(percent: BigDecimal?, caption: String, upIsGood: Boolean = false, modifier: Modifier = Modifier) {
    if (percent == null) {
        Text("Nincs korábbi adat az összehasonlításhoz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        return
    }
    val sign = percent.signum()
    val good = if (sign == 0) null else (sign > 0) == upIsGood
    val color = when (good) { true -> AppTheme.extended.positive; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant }
    val icon = when { sign > 0 -> Icons.AutoMirrored.Outlined.TrendingUp; sign < 0 -> Icons.AutoMirrored.Outlined.TrendingDown; else -> Icons.AutoMirrored.Outlined.TrendingFlat }
    val words = when { sign > 0 -> "több"; sign < 0 -> "kevesebb"; else -> "ugyanannyi" }
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = (if (sign == 0) "" else Fmt.percent(percent.abs()) + " ") + words + " " + caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CategoryBadge(category: Category?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val tint = category?.let { CategoryVisuals.color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CategoryVisuals.icon(category?.iconKey), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Amber "check this" marker: icon + word, never colour alone. */
@Composable
fun ReviewMarker(text: String = "Ellenőrizd", modifier: Modifier = Modifier) {
    val ext = AppTheme.extended
    Row(
        modifier.clip(CircleShape).background(ext.warningContainer).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = ext.onWarningContainer, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = ext.onWarningContainer)
    }
}

/** Full-width notice row for warnings and errors. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, isError: Boolean = false, icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val ext = AppTheme.extended
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else ext.warningContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else ext.onWarningContainer
    Surface(modifier.fillMaxWidth(), color = bg, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon ?: if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.ReportProblem, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = fg) }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryAction: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryAction: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (primaryAction != null && onPrimary != null) {
            Spacer(Modifier.height(Spacing.xl))
            FilledTonalButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 48.dp)) { Text(primaryAction) }
        }
        if (secondaryAction != null && onSecondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp)) { Text(secondaryAction) }
        }
    }
}

@Composable
fun LoadingState(text: String = "Betöltés…", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(Spacing.section), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

/** Amount input that accepts Hungarian formats (1 299, 1.299, 12,50). */
@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    supporting: String? = null,
) {
    val parsed = AmountParser.parseMoney(value)
    val error = value.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative))
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it in " .,-" }) },
        label = { Text(label) },
        suffix = { Text("Ft") },
        singleLine = true,
        isError = error,
        supportingText = { Text(if (error) "Érvénytelen összeg" else supporting ?: "") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyLarge.tabular,
        modifier = modifier,
    )
}

@Composable
fun DecimalField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it in ".," }) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyLarge.tabular,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate?, onDate: (LocalDate) -> Unit, label: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = "$label: ${date?.let(Fmt::date) ?: "nincs megadva"}" },
    ) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date?.let { Fmt.date(it) } ?: "Válassz dátumot", style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("Kész") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Mégse") } },
        ) { DatePicker(state = state, showModeToggle = true) }
    }
}

/** A tappable settings-style row with 48 dp minimum height. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(Spacing.lg)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(Spacing.md)); trailing() }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Vissza") }
}

/** Bottom edge of a paper receipt: a row of small triangular teeth. */
class PerforatedEdgeShape(private val toothWidth: Float = 18f, private val toothHeight: Float = 9f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = toothWidth * density.density
        val h = toothHeight * density.density
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - h)
            val teeth = (size.width / w).toInt().coerceAtLeast(1)
            val step = size.width / teeth
            var x = size.width
            for (i in 0 until teeth) {
                lineTo(x - step / 2, size.height)
                x -= step
                lineTo(x, size.height - h)
            }
            lineTo(0f, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Typography helper: weight bump for emphasized numbers in lists. */
val TextStyle.strong: TextStyle get() = copy(fontWeight = FontWeight.SemiBold)
