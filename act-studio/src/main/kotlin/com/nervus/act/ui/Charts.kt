package com.nervus.act.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

private fun DrawScope.grid(left: Float, top: Float, w: Float, h: Float, rows: Int = 4) {
    for (i in 0..rows) {
        val y = top + h * i / rows
        drawLine(Sci.line, Offset(left, y), Offset(left + w, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color = Sci.dim) {
    drawText(tm, text, at, style = Sci.small.copy(color = color, fontSize = 11.sp, fontFamily = Sci.mono))
}

/** Training loss against step, on a log scale (it falls by orders of magnitude), with a smoothed line on top. */
@Composable
fun LossChart(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val left = 56f
        val top = 10f
        val w = size.width - left - 12f
        val h = size.height - top - 26f
        grid(left, top, w, h)
        val pts = points.filter { it.second > 0 }
        if (pts.size < 2) {
            label(tm, "the loss curve appears after the first logged step", Offset(left + 12f, top + h / 2))
            return@Canvas
        }
        val x0 = pts.first().first
        val x1 = pts.last().first.coerceAtLeast(x0 + 1)
        val ys = pts.map { log10(it.second) }
        val y0 = ys.min()
        val y1 = ys.max().coerceAtLeast(y0 + 1e-6)
        fun px(x: Double) = left + ((x - x0) / (x1 - x0)).toFloat() * w
        fun py(ly: Double) = top + h - ((ly - y0) / (y1 - y0)).toFloat() * h
        val raw = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) raw.moveTo(px(x), py(log10(y))) else raw.lineTo(px(x), py(log10(y))) }
        drawPath(raw, Sci.accent.copy(alpha = 0.35f), style = Stroke(1.5f))
        // exponential moving average, the trend a person reads
        var ema = ys.first()
        val smooth = Path()
        pts.forEachIndexed { i, (x, _) ->
            ema = if (i == 0) ys[0] else 0.85 * ema + 0.15 * ys[i]
            if (i == 0) smooth.moveTo(px(x), py(ema)) else smooth.lineTo(px(x), py(ema))
        }
        drawPath(smooth, Sci.accent, style = Stroke(2.5f))
        label(tm, "%.3g".format(10.0.pow(y1)), Offset(4f, top - 2f))
        label(tm, "%.3g".format(10.0.pow(y0)), Offset(4f, top + h - 12f))
        label(tm, "step ${x0.toLong()}", Offset(left, top + h + 6f))
        val endLabel = "step ${x1.toLong()}"
        label(tm, endLabel, Offset(left + w - 9f * endLabel.length, top + h + 6f))
    }
}

/**
 * One episode's joints (each scaled to its own range) over its frames, with the part that trains between two
 * handles. Drag a handle, or click to move the nearer one; the dashed lines are the automatic idle trim.
 */
@Composable
fun TrimEditor(
    series: List<List<Double>>,
    index: List<Int>,
    frames: Int,
    start: Int,
    end: Int,
    auto: Pair<Int, Int>,
    onChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    // The gesture detectors must outlive each update, so they read the latest values instead of restarting.
    val span by rememberUpdatedState(start to end)
    val report by rememberUpdatedState(onChange)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(frames) {
                    fun frameAt(x: Float) = (((x - 8f) / (size.width - 16f)) * frames).toInt().coerceIn(0, frames)
                    detectTapGestures { p ->
                        val f = frameAt(p.x)
                        val (s, e) = span
                        if (abs(f - s) <= abs(f - e)) report(f.coerceAtMost(e - 1), e) else report(s, f.coerceAtLeast(s + 1))
                    }
                }
                .pointerInput(frames) {
                    fun frameAt(x: Float) = (((x - 8f) / (size.width - 16f)) * frames).toInt().coerceIn(0, frames)
                    var which = 0 // 1 = start, 2 = end
                    detectDragGestures(
                        onDragStart = { p ->
                            val f = frameAt(p.x)
                            val (s, e) = span
                            which = if (abs(f - s) <= abs(f - e)) 1 else 2
                        },
                    ) { change, _ ->
                        val f = frameAt(change.position.x)
                        val (s, e) = span
                        if (which == 1) report(f.coerceAtMost(e - 1), e) else report(s, f.coerceAtLeast(s + 1))
                    }
                },
        ) {
            val left = 8f
            val w = size.width - 16f
            val top = 8f
            val h = size.height - 28f
            fun px(frame: Double) = left + (frame / frames.coerceAtLeast(1)).toFloat() * w
            grid(left, top, w, h)
            drawRect(Sci.bg.copy(alpha = 0.75f), Offset(left, top), Size(px(start.toDouble()) - left, h))
            drawRect(Sci.bg.copy(alpha = 0.75f), Offset(px(end.toDouble()), top), Size(left + w - px(end.toDouble()), h))
            series.forEachIndexed { j, ys ->
                if (ys.size < 2) return@forEachIndexed
                val lo = ys.min()
                val hi = ys.max().let { if (it - lo < 1e-6) lo + 1 else it }
                val p = Path()
                ys.forEachIndexed { i, y ->
                    val x = px(index.getOrElse(i) { i }.toDouble())
                    val yy = top + h - ((y - lo) / (hi - lo)).toFloat() * h * 0.9f - h * 0.05f
                    if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
                }
                drawPath(p, Sci.jointColors[j % Sci.jointColors.size].copy(alpha = 0.9f), style = Stroke(1.6f))
            }
            for (f in listOf(auto.first, auto.second)) {
                drawLine(Sci.dim, Offset(px(f.toDouble()), top), Offset(px(f.toDouble()), top + h), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
            }
            for ((f, c) in listOf(start to Sci.ok, end to Sci.warn)) {
                val x = px(f.toDouble())
                drawLine(c, Offset(x, top), Offset(x, top + h), 2.5f)
                drawCircle(c, 6f, Offset(x, top + h))
            }
            label(tm, "frame $start", Offset(px(start.toDouble()) + 6f, top + h + 6f), Sci.ok)
            label(tm, "frame $end", Offset((px(end.toDouble()) - 70f).coerceAtLeast(left), top + 2f), Sci.warn)
        }
    }
}

/** A small bar meter for a value in a range, e.g. a joint inside its demonstrated envelope. */
@Composable
fun Meter(value: Double?, lo: Double, hi: Double, color: Color = Sci.accent, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Sci.line, Offset(0f, size.height / 2 - 2f), Size(size.width, 4f))
        val v = value ?: return@Canvas
        val f = ((v - lo) / (hi - lo).coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f)
        drawRect(color, Offset(0f, size.height / 2 - 2f), Size(size.width * f, 4f))
        drawCircle(color, 5f, Offset(size.width * f, size.height / 2))
    }
}

@Suppress("unused")
private fun lnSafe(x: Double) = if (x > 0) ln(x) else 0.0
