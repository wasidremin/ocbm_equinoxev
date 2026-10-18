package wasidremin.gmccpa

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import wasidremin.gmccpa.telemetry.InsightsData
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The "Insights" settings page: the evinsights website's overview cards and charts, drawn from
 * the probe segments already on the head unit (each `telemetry` segment directory's
 * `report.json` plus `events.ndjson`).
 *
 * The website plots full-fidelity event streams; here each segment contributes its live-callback
 * tail, so the within-segment charts are the latest segment's series and the "over time" trend
 * is per-segment last values. The page says exactly that under the title rather than pretending
 * to be the website.
 *
 * Loading runs off the UI thread ([refresh]); geometry math is pure ([chartGeom]) for JVM tests.
 */
class InsightsPage(
    private val act: Activity,
    private val dataRoot: File,
) {
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val charts = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val segments = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
    }
    private var refreshing = false

    init {
        view.addView(eqPageTitle(act, "Insights", "Your drives, from this head unit's own probe captures."))
        view.addView(eqSectionLabel(act, "Latest"))
        val card = EqCard(act)
        card.addView(tiles, LinearLayout.LayoutParams(-1, -2))
        view.addView(card)
        view.addView(eqSectionLabel(act, "Charts — latest segment"))
        view.addView(charts)
        view.addView(eqSectionLabel(act, "Segments"))
        view.addView(segments)
        view.addView(eqButton(act, "Refresh", EqButtonStyle.SECONDARY) { refresh() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = EqTheme.px(16f) })
        refresh()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        thread(name = "insights-load", isDaemon = true) {
            val data = InsightsData.loadSegments(dataRoot)
            act.runOnUiThread {
                refreshing = false
                if (act.isFinishing) return@runOnUiThread
                render(data)
            }
        }
    }

    private fun render(data: List<InsightsData.SegmentInsight>) {
        tiles.removeAllViews()
        charts.removeAllViews()
        segments.removeAllViews()
        if (data.isEmpty()) {
            tiles.addView(EqRow(act, "No probe segments yet",
                "Run the Vehicle probe (Troubleshooting) or enable auto-capture, then Refresh."))
            return
        }
        val latest = data.last()
        tiles.addView(tileGrid(listOf(
            "Battery" to InsightsData.formatBattery(latest.value(InsightsData.BATTERY_WH)),
            "Range" to InsightsData.formatRange(latest.value(InsightsData.RANGE_M)),
            "Speed" to InsightsData.formatSpeed(latest.value(InsightsData.SPEED_MPS)),
            "Outside" to InsightsData.formatTemp(latest.value(InsightsData.OUTSIDE_C)),
            "Odometer" to InsightsData.formatOdometer(latest.value(InsightsData.ODOMETER_KM)),
            "Charge" to InsightsData.formatCharge(latest.value(InsightsData.CHARGE_MW)),
        )))
        charts.addView(chartBlock(
            "Battery & range",
            latest.series[InsightsData.BATTERY_WH] ?: emptyList(),
            latest.series[InsightsData.RANGE_M] ?: emptyList(),
            "battery Wh", "range m",
        ))
        charts.addView(chartBlock(
            "Speed",
            latest.series[InsightsData.SPEED_MPS]?.map {
                it.copy(value = it.value * 3.6)
            } ?: emptyList(),
            emptyList(),
            "km/h", "",
        ))
        val trend = InsightsData.trend(data, InsightsData.BATTERY_WH)
        charts.addView(chartBlock(
            "Battery over segments",
            trend.mapIndexed { i, (_, v) -> InsightsData.SeriesPoint(i.toDouble(), v) },
            emptyList(),
            "Wh by segment", "",
        ))
        for (s in data.takeLast(10).reversed()) {
            segments.addView(EqRow(act, s.name,
                "${s.startWall} · ${s.events} events · " +
                    "battery ${InsightsData.formatBattery(s.value(InsightsData.BATTERY_WH))}",
                onClick = {
                    Toast.makeText(act,
                        "${s.name}: speed ${InsightsData.formatSpeed(s.value(InsightsData.SPEED_MPS))}, " +
                            "range ${InsightsData.formatRange(s.value(InsightsData.RANGE_M))}",
                        Toast.LENGTH_LONG).show()
                }))
        }
    }

    private fun tileGrid(items: List<Pair<String, String>>): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(EqTheme.px(28f), EqTheme.px(18f), EqTheme.px(24f), EqTheme.px(18f))
            for (row in items.chunked(2)) {
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for ((label, value) in row) {
                        addView(LinearLayout(act).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, EqTheme.px(8f), EqTheme.px(24f), EqTheme.px(8f))
                            addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
                                text = label.uppercase()
                                letterSpacing = 0.12f
                            })
                            addView(EqTheme.text(act, 24f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply {
                                text = value
                            })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                })
            }
        }

    private fun chartBlock(
        title: String,
        a: List<InsightsData.SeriesPoint>,
        b: List<InsightsData.SeriesPoint>,
        labelA: String,
        labelB: String,
    ): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, EqTheme.px(8f), 0, EqTheme.px(16f))
        addView(EqTheme.text(act, 20f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply { text = title })
        val legend = listOf(
            if (a.isNotEmpty()) "— $labelA" else null,
            if (b.isNotEmpty()) "— $labelB" else null,
        ).filterNotNull().joinToString("   ").ifEmpty { "no samples in this segment" }
        addView(EqTheme.text(act, 14f, EqTheme.TEXT_DIM).apply { text = legend })
        addView(InsightChartView(act).apply { setData(a, b) },
            LinearLayout.LayoutParams(-1, EqTheme.px(300f)).apply { topMargin = EqTheme.px(8f) })
    }
}

/** Dual-normalised line chart: each series scales to its own min/max (website overview style). */
internal class InsightChartView(ctx: android.content.Context) : View(ctx) {
    private var a: List<InsightsData.SeriesPoint> = emptyList()
    private var b: List<InsightsData.SeriesPoint> = emptyList()
    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9BFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CC38A.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF98A2B3.toInt(); textSize = 30f
    }

    fun setData(a: List<InsightsData.SeriesPoint>, b: List<InsightsData.SeriesPoint>) {
        this.a = a
        this.b = b
        contentDescription = "Chart: ${a.size} and ${b.size} samples"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val g = chartGeom(a, b, width.toFloat(), height.toFloat(), 56f)
        canvas.drawLine(g.pad, g.pad, g.pad, height - g.pad, paintGrid)
        canvas.drawLine(g.pad, height - g.pad, width - g.pad, height - g.pad, paintGrid)
        drawSeries(canvas, g.ptsA, paintA)
        drawSeries(canvas, g.ptsB, paintB)
        canvas.drawText(g.labelA, g.pad, g.pad - 12f, paintText)
        canvas.drawText(g.labelB, width - g.pad - paintText.measureText(g.labelB), g.pad - 12f, paintText)
    }

    private fun drawSeries(canvas: Canvas, pts: List<Pair<Float, Float>>, paint: Paint) {
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            canvas.drawLine(x0, y0, x1, y1, paint)
        }
    }
}

internal data class ChartGeom(
    val ptsA: List<Pair<Float, Float>>,
    val ptsB: List<Pair<Float, Float>>,
    val labelA: String,
    val labelB: String,
    val pad: Float,
)

/** Pure chart geometry: normalise each series onto its own min/max inside [w]×[h] minus [pad]. */
internal fun chartGeom(
    a: List<InsightsData.SeriesPoint>,
    b: List<InsightsData.SeriesPoint>,
    w: Float,
    h: Float,
    pad: Float,
): ChartGeom {
    fun norm(
        s: List<InsightsData.SeriesPoint>,
        tMin: Double,
        tSpan: Double,
    ): List<Pair<Float, Float>> {
        if (s.isEmpty()) return emptyList()
        val vMin = s.minOf { it.value }
        val vSpan = s.maxOf { it.value } - vMin
        return s.map { p ->
            val x = pad + ((p.tSec - tMin) / tSpan * (w - 2 * pad)).toFloat()
            // A flat series parks mid-box: bottom would read as "zero", which it is not.
            val y = if (vSpan <= 0) h / 2f
                else (h - pad - (p.value - vMin) / vSpan * (h - 2 * pad)).toFloat()
            x to y
        }
    }
    fun label(s: List<InsightsData.SeriesPoint>): String {
        if (s.isEmpty()) return ""
        val lo = s.minOf { it.value }
        val hi = s.maxOf { it.value }
        return "${fmtShort(lo)}…${fmtShort(hi)}"
    }
    val all = a + b
    val tMin = all.minOfOrNull { it.tSec } ?: 0.0
    val tSpan = ((all.maxOfOrNull { it.tSec } ?: 0.0) - tMin).takeIf { it > 0 } ?: 1.0
    return ChartGeom(norm(a, tMin, tSpan), norm(b, tMin, tSpan), label(a), label(b), pad)
}

internal fun fmtShort(v: Double): String = when {
    v.isNaN() || v.isInfinite() -> "—"
    kotlin.math.abs(v) >= 10000 -> "${"%.1f".format(v / 1000)}k"
    v == v.roundToInt().toDouble() && kotlin.math.abs(v) < 1e6 -> v.roundToInt().toString()
    kotlin.math.abs(v) >= 1 -> "${"%.1f".format(v)}"
    else -> "${"%.3f".format(v)}"
}
