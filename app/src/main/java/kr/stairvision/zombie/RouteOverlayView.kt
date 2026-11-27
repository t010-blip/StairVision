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

    private var from: Point? = null
    private var targets: List<Point> = emptyList()
    private val loc = IntArray(2)

    private val d = ctx.resources.displayMetrics.density
    private val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 214, 0)
        strokeWidth = 4f * d
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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

    /** 좌표는 화면 픽셀. 메인 스레드에서 호출. */
    fun show(from: Point?, targets: List<Point>) {
        this.from = from
        this.targets = targets
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val start = from ?: return
        if (targets.isEmpty()) return
        // 오버레이 창이 화면 원점과 어긋난 만큼(노치·상태바) 보정
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        if (targets.size > 1) {
            val p = Path().apply {
                moveTo(targets[0].x, targets[0].y)
                targets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(p, next)
        }
        val t0 = targets[0]
        canvas.drawLine(start.x, start.y, t0.x, t0.y, main)
        arrowHead(canvas, start, t0)

        targets.forEachIndexed { i, t ->
            val r = (if (i == 0) 13f else 10f) * d
            dot.color = if (i == 0) Color.rgb(255, 214, 0) else Color.argb(220, 255, 255, 255)
            canvas.drawCircle(t.x, t.y, r, dot)
            canvas.drawText("${i + 1}", t.x, t.y + 4f * d, num)
            if (i == 0) canvas.drawText(t.label, t.x + r + 4f * d, t.y - r, label)
        }
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
