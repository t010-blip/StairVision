package kr.stairvision

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kr.stairvision.analysis.Calibration
import kr.stairvision.analysis.IntArraySource
import kr.stairvision.analysis.NPoint
import kr.stairvision.analysis.StairAnalyzer
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * 저장된 게임 화면 한 장 위에서 네 곳을 차례로 지정한다.
 * 현재·다음 발판으로 한 칸의 간격과 발판 색을 얻고, 두 버튼 위치는 자동 입력 좌표가 된다.
 */
class CalibrationActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var view: CalibrationView
    private lateinit var prompt: TextView
    private lateinit var result: TextView
    private var source: IntArraySource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val file = File(filesDir, Prefs.CALIBRATION_FRAME)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            setContentView(TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 16f
                text = "보정용 화면이 없습니다.\n\n③ 분석 시작 후 게임 화면에서 오버레이의 '보정캡처'를 누르고 다시 오세요."
            })
            return
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        source = IntArraySource(px, bmp.width, bmp.height)

        prompt = TextView(this).apply { textSize = 15f; setPadding(24, 16, 24, 8) }
        result = TextView(this).apply { textSize = 13f; setPadding(24, 8, 24, 8) }
        view = CalibrationView(this, bmp).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onChanged = { refresh() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "되돌리기"
                setOnClickListener { view.undo() }
            })
            addView(Button(context).apply {
                text = "저장"
                setOnClickListener { save() }
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(prompt)
            addView(view)
            addView(result)
            addView(buttons)
        })
        refresh()
    }

    private fun refresh() {
        val n = view.points.size
        prompt.text = if (n < STEPS.size) "${n + 1}/4 · ${STEPS[n]}을(를) 누르세요" else "4곳 지정 완료 · 결과를 확인하고 저장하세요"
        val cal = build()
        result.text = when {
            n < 2 -> "현재 발판은 캐릭터에 가려지지 않은 부분(발 바로 아래)을 누르세요."
            cal == null -> "다음 발판은 현재 발판보다 위에, 옆으로 떨어진 곳이어야 합니다."
            else -> {
                val a = StairAnalyzer(prefs.analyzerParams()).analyze(source!!, cal)
                String.format(
                    Locale.KOREA,
                    "검증: 다음 발판 = %s · 신뢰도 %.2f\n현재 %.2f · 왼쪽 위 %.2f · 오른쪽 위 %.2f\n기준 색 #%06X",
                    a.nextSide?.label() ?: "판단 불가", a.confidence,
                    a.currentScore, a.leftScore, a.rightScore, cal.stepColor,
                )
            }
        }
    }

    /** 앞의 두 점만 있어도 기하·색을 계산한다. 버튼 좌표는 없으면 임시값. */
    private fun build(): Calibration? {
        val p = view.points
        val src = source ?: return null
        if (p.size < 2) return null
        val cur = p[0]
        val next = p[1]
        val draft = Calibration(cur, next, p.getOrElse(2) { cur }, p.getOrElse(3) { cur }, 0)
        if (!draft.isValid()) return null
        val ratio = prefs.analyzerParams().patchRatio
        val color = StairAnalyzer.medianColor(
            src, cur.x * src.width, cur.y * src.height,
            max(2f, draft.dx * src.width * ratio), max(2f, draft.dy * src.height * ratio),
        )
        return draft.copy(stepColor = color)
    }

    private fun save() {
        if (view.points.size < 4) {
            toast("네 곳을 모두 지정하세요")
            return
        }
        val cal = build() ?: run {
            toast("발판 위치를 다시 지정하세요")
            return
        }
        prefs.saveCalibration(cal)
        toast("보정을 저장했습니다")
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        val STEPS = listOf("현재 발판", "다음 발판(바로 위 칸)", "오르기 버튼", "방향 전환 버튼")
    }
}

@SuppressLint("ViewConstructor")
class CalibrationView(ctx: Context, private val bitmap: Bitmap) : View(ctx) {
    val points = mutableListOf<NPoint>()
    var onChanged: (() -> Unit)? = null

    private val dst = RectF()
    private val colors = intArrayOf(Color.GREEN, Color.CYAN, Color.YELLOW, Color.MAGENTA)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun undo() {
        if (points.isNotEmpty()) {
            points.removeAt(points.lastIndex)
            invalidate()
            onChanged?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, dst, null)
        points.forEachIndexed { i, p ->
            val x = dst.left + p.x * dst.width()
            val y = dst.top + p.y * dst.height()
            dot.color = colors[i]
            text.color = colors[i]
            canvas.drawCircle(x, y, 18f, dot)
            canvas.drawLine(x - 26f, y, x + 26f, y, dot)
            canvas.drawLine(x, y - 26f, x, y + 26f, dot)
            canvas.drawText("${i + 1}", x + 22f, y - 22f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && points.size < 4 && dst.contains(e.x, e.y)) {
            points += NPoint((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height())
            invalidate()
            onChanged?.invoke()
        }
        return true
    }
}
