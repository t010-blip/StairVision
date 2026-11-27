package kr.stairvision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

data class OverlayState(
    val headline: String,
    val detail: String,
    val status: String,
    val autoOn: Boolean,
)

/**
 * 게임 위에 뜨는 작은 상태 패널.
 * 글자 부분을 끌면 위치를 옮길 수 있다. 계단 주변과 게임 버튼을 가리지 않는 곳에 둘 것:
 * 전체 화면 캡처에는 이 패널도 함께 찍히고, 탭도 패널이 먼저 받는다.
 */
class OverlayController(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onToggleAuto()
        fun onFlipFacing()
        fun onCaptureCalibration()
        fun onEmergencyStop()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var status: TextView
    private lateinit var autoButton: TextView

    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
        }
        headline = label(15f, true)
        detail = label(11f, false)
        status = label(11f, false)
        panel.addView(headline)
        panel.addView(detail)
        panel.addView(status)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        autoButton = button("자동 OFF", Color.DKGRAY) { listener.onToggleAuto() }
        row.addView(autoButton)
        row.addView(button("방향↔", Color.DKGRAY) { listener.onFlipFacing() })
        row.addView(button("보정캡처", Color.DKGRAY) { listener.onCaptureCalibration() })
        row.addView(button("■ 중지", Color.rgb(200, 30, 30)) { listener.onEmergencyStop() })
        panel.addView(row)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(40)
        }
        enableDrag(panel, listOf(headline, detail, status))
        wm.addView(panel, params)
        root = panel
        update(OverlayState("준비 중", "", "", false))
        return true
    }

    fun update(s: OverlayState) {
        if (root == null) return
        headline.text = s.headline
        detail.text = s.detail
        status.text = s.status
        autoButton.text = if (s.autoOn) "자동 ON" else "자동 OFF"
        (autoButton.background as GradientDrawable)
            .setColor(if (s.autoOn) Color.rgb(20, 140, 60) else Color.DKGRAY)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun label(sp: Float, bold: Boolean) = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(panel: View, handles: List<View>) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, params)
                }
            }
            true
        }
        handles.forEach { it.setOnTouchListener(drag) }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
