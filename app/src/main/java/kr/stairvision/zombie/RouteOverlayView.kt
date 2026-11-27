package kr.stairvision.zombie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 게임 위에 경로를 그리는 투명한 전체 화면 뷰(터치는 통과).
 * 캐릭터 → 지금 갈 곳은 굵은 실선과 화살표, 그다음 지점들은 가는 점선.
 */
class RouteOverlayView(ctx: Context) : View(ctx) {

    data class Point(val x: Float, val y: Float, val label: String)

    /** 한 구간: 꺾인 점들(화면 픽셀)과 도착 지점 이름. */
    data class Leg(val points: List<Pair<Float, Float>>, val label: String)

    private var legs: List<Leg> = emptyList()
    private val loc = IntArray(2)

    private val d = ctx.resources.displayMetrics.density
    private val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 214, 0)
        strokeWidth = 4f * d
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val next = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        strokeWidth = 2f * d
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * d
        isFakeBoldText = true
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }

    /** 좌표는 화면 픽셀. 첫 구간은 캐릭터에서 시작한다. 메인 스레드에서 호출. */
    fun show(legs: List<Leg>) {
        this.legs = legs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (legs.isEmpty()) return
        // 오버레이 창이 화면 원점과 어긋난 만큼(노치·상태바) 보정
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        legs.drop(1).forEach { canvas.drawPath(pathOf(it.points), next) }
        val first = legs[0].points
        canvas.drawPath(pathOf(first), main)
        if (first.size >= 2) {
            val a = first[first.size - 2]
            val b = first.last()
            arrowHead(canvas, Point(a.first, a.second, ""), Point(b.first, b.second, ""))
        }

        legs.forEachIndexed { i, leg ->
            val t = leg.points.lastOrNull() ?: return@forEachIndexed
            val r = (if (i == 0) 13f else 10f) * d
            dot.color = if (i == 0) Color.rgb(255, 214, 0) else Color.argb(220, 255, 255, 255)
            canvas.drawCircle(t.first, t.second, r, dot)
            canvas.drawText("${i + 1}", t.first, t.second + 4f * d, num)
            if (i == 0) canvas.drawText(leg.label, t.first + r + 4f * d, t.second - r, label)
        }
    }

    private fun pathOf(pts: List<Pair<Float, Float>>) = Path().apply {
        pts.firstOrNull()?.let { moveTo(it.first, it.second) }
        pts.drop(1).forEach { lineTo(it.first, it.second) }
    }

    private fun arrowHead(c: Canvas, a: Point, b: Point) {
        val ang = atan2(b.y - a.y, b.x - a.x)
        val back = 18f * d
        val tipX = b.x - cos(ang) * 14f * d
        val tipY = b.y - sin(ang) * 14f * d
        for (s in listOf(-0.5f, 0.5f)) {
            c.drawLine(tipX, tipY, tipX - cos(ang + s) * back, tipY - sin(ang + s) * back, main)
        }
    }
}
