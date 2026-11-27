package kr.stairvision.zombie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 좀비고 크리스마스 익스프레스 2 가이드 오버레이.
 * 화면 상단 주문을 '+주문'으로 고르면, 만드는 순서와 필요한 꽃 합계를 게임 위에 띄운다.
 * 화면 캡처나 자동 입력은 쓰지 않는다.
 */
class GuideService : Service() {

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var header: TextView
    private lateinit var orderList: LinearLayout
    private lateinit var totals: TextView
    private lateinit var picker: LinearLayout
    private lateinit var pickerTitle: TextView

    private val orders = mutableListOf<Order>()
    private var nextId = 1
    private var collapsed = false
    private var setMode = false
    private var pendingFirst: Figure? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root != null) return START_NOT_STICKY
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "다른 앱 위에 표시 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WindowManager::class.java)
        showPanel()
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    // ---------- 화면 구성 ----------

    private fun showPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 15, 25, 35))
                cornerRadius = dp(10).toFloat()
            }
        }
        header = text(14f, bold = true)
        panel.addView(header)

        orderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(orderList)
        totals = text(12f, bold = false).apply {
            setTextColor(Color.rgb(255, 214, 120))
            setPadding(0, dp(4), 0, 0)
        }
        panel.addView(totals)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(button("+주문", Color.rgb(20, 120, 70)) { openPicker() })
        row.addView(button("접기", Color.DKGRAY) { collapsed = !collapsed; refresh() })
        row.addView(button("비우기", Color.DKGRAY) { orders.clear(); refresh() })
        row.addView(button("닫기", Color.rgb(170, 40, 40)) { stopSelf() })
        panel.addView(row)

        picker = buildPicker()
        picker.visibility = View.GONE
        panel.addView(picker)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(90)
        }
        enableDrag(panel, header)
        wm.addView(panel, params)
        root = panel
        refresh()
    }

    private fun buildPicker(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        pickerTitle = text(12f, bold = true)
        box.addView(pickerTitle)

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(button("단품", Color.DKGRAY) { setMode = false; pendingFirst = null; updatePickerTitle() })
        modes.addView(button("세트", Color.DKGRAY) { setMode = true; pendingFirst = null; updatePickerTitle() })
        modes.addView(button("취소", Color.rgb(120, 60, 60)) { closePicker() })
        box.addView(modes)

        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(4), 0, 0)
        }
        Recipes.figures.forEach { f ->
            grid.addView(TextView(this).apply {
                text = f.short
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(45, 60, 80))
                    cornerRadius = dp(5).toFloat()
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(62)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                setOnClickListener { onFigurePicked(f) }
            })
        }
        box.addView(grid)
        return box
    }

    private fun refresh() {
        header.text = "크익2 가이드 · 주문 ${orders.size}개"
        orderList.removeAllViews()
        val shown = if (collapsed) orders.take(1) else orders
        shown.forEachIndexed { i, o ->
            val lines = Planner.steps(o).joinToString("\n") { "   $it" }
            orderList.addView(text(12f, bold = false).apply {
                text = "${CIRCLED.getOrElse(i) { "${i + 1}." }} ${o.title}\n$lines"
                setPadding(0, dp(4), 0, dp(2))
                setOnClickListener { complete(o) }
            })
        }
        if (collapsed && orders.size > 1) {
            orderList.addView(text(11f, bold = false).apply { text = "   … 외 ${orders.size - 1}개" })
        }
        totals.text = if (orders.isEmpty()) {
            "'+주문'으로 화면 상단 주문을 추가하세요.\n주문을 누르면 완료 처리됩니다."
        } else {
            "꽃 합계: " + Planner.flowerTotals(orders).joinToString(" · ") { (f, n) -> "$f $n" }
        }
    }

    // ---------- 동작 ----------

    private fun openPicker() {
        setMode = false
        pendingFirst = null
        updatePickerTitle()
        picker.visibility = View.VISIBLE
    }

    private fun closePicker() {
        pendingFirst = null
        picker.visibility = View.GONE
    }

    private fun updatePickerTitle() {
        pickerTitle.text = when {
            !setMode -> "단품 주문: 피규어를 고르세요"
            pendingFirst == null -> "세트 주문: 첫 번째 피규어"
            else -> "세트 주문: ${pendingFirst!!.name} + 두 번째 피규어"
        }
    }

    private fun onFigurePicked(f: Figure) {
        if (!setMode) {
            addOrder(listOf(f))
            return
        }
        val first = pendingFirst
        if (first == null) {
            pendingFirst = f
            updatePickerTitle()
            return
        }
        if (first == f) {
            Toast.makeText(this, "같은 피규어 두 개는 폴라로이드가 되어 쓸모없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        addOrder(listOf(first, f))
    }

    private fun addOrder(figs: List<Figure>) {
        orders += Order(nextId++, figs)
        closePicker()
        refresh()
    }

    private fun complete(o: Order) {
        orders.remove(o)
        refresh()
    }

    // ---------- 보조 ----------

    private fun text(sp: Float, bold: Boolean) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxWidth = dp(280)
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
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
    private fun enableDrag(panel: View, handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    // gravity END: x는 오른쪽에서의 거리
                    params.x = startX - (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, params)
                }
            }
            true
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "크익2 가이드", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("크익2 가이드 표시 중")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .addAction(Notification.Action.Builder(null, "닫기", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "guide"
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "kr.stairvision.GUIDE_STOP"
        private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧")

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuideService::class.java))
        }
    }
}
