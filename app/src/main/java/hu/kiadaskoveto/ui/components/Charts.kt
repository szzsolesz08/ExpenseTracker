package hu.kiadaskoveto.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.AppTheme
import hu.kiadaskoveto.ui.theme.Spacing
import hu.kiadaskoveto.ui.theme.tabular
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class ColumnDatum(val label: String, val value: Long, val emphasized: Boolean = false, val tooltipTitle: String = label)

/** Clean axis ticks: 0 and two round steps above the maximum. */
private fun niceTicks(maxValue: Long): List<Long> {
    if (maxValue <= 0) return listOf(0, 100_000, 200_000)
    val raw = maxValue / 2.0
    val mag = 10.0.pow(kotlin.math.floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val s = (ceil(step / 100.0) * 100).toLong().coerceAtLeast(100)
    return listOf(0L, s, 2 * s)
}

@Composable
private fun rememberGrowth(key: Any): Float {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { anim.animateTo(1f, tween(durationMillis = 650, easing = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
    return anim.value
}

/**
 * Single-series column chart (emphasis form): emphasized columns in the accent, the rest in the
 * de-emphasis grey. Tap a column for its value. Values are minor units (fillér).
 */
@Composable
fun ColumnChart(
    data: List<ColumnDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    summary: String,
    labelEvery: Int = 1,
    valueLabel: Boolean = true,
) {
    val ext = AppTheme.extended
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant).tabular
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface).tabular
    var selected by remember(data) { mutableIntStateOf(-1) }
    val growth = rememberGrowth(data.size)
    val density = LocalDensity.current
    val maxValue = data.maxOfOrNull { it.value } ?: 0
    val ticks = niceTicks(maxValue)
    val top = ticks.last().toFloat()

    Box(modifier.semantics { contentDescription = summary }) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(data) {
                detectTapGestures { pos ->
                    val axisW = with(density) { 36.dp.toPx() }
                    val slot = (size.width - axisW) / data.size.coerceAtLeast(1)
                    val i = ((pos.x - axisW) / slot).toInt()
                    selected = if (i in data.indices && i != selected) i else -1
                }
            }
        ) {
            if (data.isEmpty()) return@Canvas
            val axisW = 36.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val topPad = 20.dp.toPx()
            val plotH = size.height - bottomPad - topPad
            val baseY = topPad + plotH
            // Hairline grid + tick labels
            ticks.forEach { t ->
                val y = baseY - plotH * (t / top)
                drawLine(ext.chartGrid, Offset(axisW, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                val text = measurer.measure(Fmt.compact(t), axisStyle)
                drawText(text, topLeft = Offset(axisW - text.size.width - 6.dp.toPx(), y - text.size.height / 2))
            }
            val slot = (size.width - axisW) / data.size
            val barW = minOf(24.dp.toPx(), slot * 0.62f)
            data.forEachIndexed { i, d ->
                val cx = axisW + slot * i + slot / 2
                val h = if (d.value <= 0) 0f else max(2.dp.toPx(), plotH * (d.value / top) * growth)
                val color = if (d.emphasized || i == selected) ext.chartAccent else ext.chartMuted
                if (h > 0f) drawTopRoundedBar(cx - barW / 2, baseY - h, barW, h, 4.dp.toPx(), color)
                val nearEmphasized = !d.emphasized && labelEvery > 1 && data.indices.any { j -> data[j].emphasized && kotlin.math.abs(j - i) < (labelEvery + 1) / 2 }
                if ((i % labelEvery == 0 && !nearEmphasized) || d.emphasized) {
                    val lab = measurer.measure(d.label, axisStyle)
                    drawText(lab, topLeft = Offset(cx - lab.size.width / 2, baseY + 5.dp.toPx()))
                }
                if (valueLabel && d.emphasized && selected < 0 && d.value > 0) {
                    val v = measurer.measure(Fmt.compact(d.value), valueStyle)
                    drawText(v, topLeft = Offset((cx - v.size.width / 2).coerceIn(axisW, size.width - v.size.width), baseY - h - v.size.height - 2.dp.toPx()))
                }
            }
            drawLine(ext.chartGrid.copy(alpha = 1f), Offset(axisW, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
        }
        if (selected in data.indices) {
            val d = data[selected]
            ChartTooltip(d.tooltipTitle, Fmt.money(d.value), Modifier.align(Alignment.TopCenter))
        }
    }
}

private fun DrawScope.drawTopRoundedBar(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
    val radius = minOf(r, h, w / 2)
    val path = Path().apply {
        addRoundRect(RoundRect(left = x, top = y, right = x + w, bottom = y + h,
            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero))
    }
    drawPath(path, color)
}

@Composable
fun ChartTooltip(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.inverseOnSurface)
            Text(value, style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

data class RankedRow(val label: String, val value: Long, val leading: (@Composable () -> Unit)? = null, val onClick: (() -> Unit)? = null)

/**
 * Ranked horizontal bars (part-to-whole for many parts): one hue, largest first,
 * name + figure + share per row. Identity is carried by the label, never by colour.
 */
@Composable
fun RankedBars(rows: List<RankedRow>, modifier: Modifier = Modifier, total: Long = rows.sumOf { it.value }) {
    val ext = AppTheme.extended
    val maxValue = rows.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    val growth = rememberGrowth(rows.size)
    Column(modifier) {
        rows.forEach { row ->
            val share = if (total > 0) row.value * 100.0 / total else 0.0
            val rowModifier = if (row.onClick != null) Modifier.fillMaxWidth().padding(vertical = 2.dp).then(
                Modifier.semantics(mergeDescendants = true) {}
            ) else Modifier.fillMaxWidth()
            Surface(
                onClick = row.onClick ?: {},
                enabled = row.onClick != null,
                color = Color.Transparent,
                shape = MaterialTheme.shapes.small,
                modifier = rowModifier,
            ) {
                Row(Modifier.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    row.leading?.let { it(); Spacer(Modifier.width(Spacing.md)) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(Spacing.sm))
                            Text(Fmt.money(row.value), style = MaterialTheme.typography.bodyMedium.tabular, maxLines = 1)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.weight(1f).height(8.dp)) {
                                val w = size.width * (row.value.coerceAtLeast(0).toFloat() / maxValue) * growth
                                drawRoundRect(ext.chartMuted.copy(alpha = 0.35f), size = size, cornerRadius = CornerRadius(4.dp.toPx()))
                                if (w > 0) drawRoundRect(ext.chartAccent, size = Size(max(w, 4.dp.toPx()), size.height), cornerRadius = CornerRadius(4.dp.toPx()))
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(String.format(Fmt.HU, "%.0f%%", share), style = MaterialTheme.typography.labelSmall.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                        }
                    }
                }
            }
        }
    }
}

data class LinePoint(val label: String, val value: Long, val tooltip: String = label)

/** Single-series line (2 dp) with a ringed end marker and a 10 % area wash. Tap for values. */
@Composable
fun LineChart(points: List<LinePoint>, modifier: Modifier = Modifier, height: Dp = 180.dp, summary: String, valueFormatter: (Long) -> String = Fmt::money) {
    val ext = AppTheme.extended
    val surface = ext.chartSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant).tabular
    var selected by remember(points) { mutableIntStateOf(-1) }
    val growth = rememberGrowth(points.size)
    val density = LocalDensity.current
    Box(modifier.semantics { contentDescription = summary }) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(points) {
                detectTapGestures { pos ->
                    if (points.size < 2) return@detectTapGestures
                    val axisW = with(density) { 44.dp.toPx() }
                    val step = (size.width - axisW - 12f) / (points.size - 1)
                    val i = ((pos.x - axisW) / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, points.size - 1)
                    selected = if (i == selected) -1 else i
                }
            }
        ) {
            if (points.isEmpty()) return@Canvas
            val axisW = 44.dp.toPx()
            val topPad = 16.dp.toPx(); val bottomPad = 22.dp.toPx()
            val plotH = size.height - topPad - bottomPad
            val baseY = topPad + plotH
            val minV = points.minOf { it.value }
            val maxV = points.maxOf { it.value }
            val span = (maxV - minV).coerceAtLeast(1)
            val lo = (minV - span * 0.25).toLong().coerceAtLeast(0)
            val hi = maxV + span / 4 + 1
            fun y(v: Long) = baseY - plotH * ((v - lo).toFloat() / (hi - lo))
            listOf(lo, (lo + hi) / 2, hi).forEach { t ->
                val yy = y(t)
                drawLine(ext.chartGrid, Offset(axisW, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                val txt = measurer.measure(valueFormatter(t).replace(" Ft", ""), axisStyle)
                drawText(txt, topLeft = Offset(axisW - txt.size.width - 6.dp.toPx(), yy - txt.size.height / 2))
            }
            val step = if (points.size > 1) (size.width - axisW - 12.dp.toPx()) / (points.size - 1) else 0f
            val xs = points.indices.map { axisW + 6.dp.toPx() + step * it }
            val visible = (points.size * growth).toInt().coerceIn(1, points.size)
            val line = Path().apply { points.take(visible).forEachIndexed { i, p -> if (i == 0) moveTo(xs[i], y(p.value)) else lineTo(xs[i], y(p.value)) } }
            val area = Path().apply { addPath(line); lineTo(xs[visible - 1], baseY); lineTo(xs[0], baseY); close() }
            drawPath(area, ext.chartAccent.copy(alpha = 0.10f))
            drawPath(line, ext.chartAccent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val markIndex = if (selected >= 0) selected else visible - 1
            if (selected >= 0) drawLine(ext.chartMuted, Offset(xs[markIndex], topPad), Offset(xs[markIndex], baseY), strokeWidth = 1.dp.toPx())
            drawCircle(surface, radius = 6.dp.toPx(), center = Offset(xs[markIndex], y(points[markIndex].value)))
            drawCircle(ext.chartAccent, radius = 4.dp.toPx(), center = Offset(xs[markIndex], y(points[markIndex].value)))
            listOf(0, points.size - 1).distinct().forEach { i ->
                val lab = measurer.measure(points[i].label, axisStyle)
                val x = (xs[i] - lab.size.width / 2).coerceIn(axisW, size.width - lab.size.width)
                drawText(lab, topLeft = Offset(x, baseY + 5.dp.toPx()))
            }
        }
        if (selected in points.indices) {
            ChartTooltip(points[selected].tooltip, valueFormatter(points[selected].value), Modifier.align(Alignment.TopCenter))
        }
    }
}


/**
 * Budget meter: the track is a light step of the fill's own hue; the fill turns amber from 75 %
 * and red from 100 %. Callers always pair it with an icon + text status.
 */
@Composable
fun Meter(ratio: Double, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val ext = AppTheme.extended
    val fill = when {
        ratio >= 1.0 -> MaterialTheme.colorScheme.error
        ratio >= 0.75 -> ext.warning
        else -> ext.chartAccent
    }
    val growth = rememberGrowth(ratio)
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRoundRect(fill.copy(alpha = 0.18f), size = size, cornerRadius = CornerRadius(size.height / 2))
        val w = size.width * ratio.coerceIn(0.0, 1.0).toFloat() * growth
        if (w > 0f) drawRoundRect(fill, size = Size(max(w, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

