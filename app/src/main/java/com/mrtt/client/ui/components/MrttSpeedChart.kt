package com.mrtt.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.mrtt.client.TrafficMonitor

private const val CHART_TICK_MS = 100L

@Composable
fun MrttSpeedChart(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    showGrid: Boolean = true,
) {
    val samples by TrafficMonitor.samples.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val downColor = scheme.primary
    val upColor = scheme.tertiary
    val grid = scheme.outlineVariant
    // Часы графика: окно едет влево ровно настолько, насколько приходит новых
    // точек (Rust присылает STATS раз в 100-250 мс). Раньше здесь был цикл на
    // каждый кадр — он перерисовывал весь график 60 раз в секунду и на слабых
    // телефонах заметно подтормаживал. Десять тиков в секунду дают ту же
    // плавность при вчетверо меньшей работе.
    var nowMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CHART_TICK_MS)
            nowMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    // Отбор точек и потолок считаем только когда меняются данные или время, а
    // не на каждой перерисовке: список из сотен точек фильтровался чаще, чем
    // обновлялся.
    val visible = remember(samples, nowMs) {
        samples.filter { it.atMs <= nowMs && nowMs - it.atMs <= TrafficMonitor.WINDOW_MS + 2_000 }
    }
    // «Красивый» потолок шкалы: не меньше 1 Мбит/с, чтобы тишина не раздувалась
    // на весь график. Считаем с запасом по пику, чтобы шкала не прыгала от
    // каждой случайной точки.
    val ceiling = remember(visible) {
        val peak = visible.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0f
        niceCeiling(maxOf(peak * 1.15f, 125_000f))
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val pad = 4.dp.toPx()
        val top = pad
        val bottom = h - pad
        val window = TrafficMonitor.WINDOW_MS.toFloat()

        if (showGrid) {
            repeat(4) { i ->
                val y = top + (bottom - top) * i / 3f
                drawLine(grid.copy(alpha = if (i == 3) 0.6f else 0.28f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            }
        }
        if (visible.size >= 2) {
            // Точка не должна уезжать за край: иначе «будущие» или очень
            // старые замеры рисовались поверх другого содержимого экрана.
            fun x(at: Long) = (w - (nowMs - at) / window * w).coerceIn(0f, w)
            fun y(value: Float) = bottom - (value / ceiling).coerceIn(0f, 1f) * (bottom - top)
            drawSeries(visible.map { x(it.atMs) to y(it.upBps) }, upColor, bottom, fill = 0.14f)
            drawSeries(visible.map { x(it.atMs) to y(it.downBps) }, downColor, bottom, fill = 0.26f)
            val last = visible.last()
            val lx = x(last.atMs).coerceIn(0f, w)
            drawCircle(downColor, 3.2.dp.toPx(), Offset(lx, y(last.downBps)))
        }
    }
}

private fun niceCeiling(value: Float): Float {
    // Потолок кратен 1, 2, 5 × 10^n байт/с, чтобы подписи шкалы были ровными.
    var base = 125_000f
    while (base < value) {
        base *= when {
            base * 2f >= value -> 2f
            base * 2.5f >= value -> 2.5f
            else -> 4f
        }
    }
    return base
}

private fun DrawScope.drawSeries(points: List<Pair<Float, Float>>, color: Color, bottom: Float, fill: Float) {
    if (points.size < 2) return
    val line = Path().apply {
        moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            val mid = (x0 + x1) / 2f
            cubicTo(mid, y0, mid, y1, x1, y1)
        }
    }
    val area = Path().apply {
        addPath(line)
        lineTo(points.last().first, bottom)
        lineTo(points.first().first, bottom)
        close()
    }
    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = fill), Color.Transparent), 0f, bottom))
    drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
fun MrttSpeedReadout(modifier: Modifier = Modifier) {
    val totals by TrafficMonitor.totals.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↓ " + TrafficMonitor.formatSpeed(totals.lastDownBps),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.primary,
        )
        Text(
            text = "↑ " + TrafficMonitor.formatSpeed(totals.lastUpBps),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.tertiary,
        )
    }
}
