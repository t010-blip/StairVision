package kr.stairvision.zombie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import kr.stairvision.analysis.RgbaBufferSource
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * 좀비고 크리스마스 익스프레스 2 가이드.
 * - 패널: 주문을 고르면 제작 순서와 꽃 합계를 보여준다.
 * - 경로: 화면을 캡처해 카메라 이동을 추적하고, 첫 주문을 끝내는 동선을 게임 위에 선으로 그린다.
 * 자동 입력은 하지 않는다.
 */
class GuideService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var store: MapStore

    // 패널
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var header: TextView
    private lateinit var routeText: TextView
    private lateinit var orderList: LinearLayout
    private lateinit var totals: TextView
    private lateinit var picker: LinearLayout
    private lateinit var pickerTitle: TextView
    private var routeView: RouteOverlayView? = null

    // 주문
    private val orders = mutableListOf<Order>()
    private var nextId = 1
    private var collapsed = false
    private var setMode = false
    private var pendingFirst: Figure? = null

    // 캡처·추적 (분석 스레드)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val tracker = CameraTracker(TrackerParams(regionTop = 0.28f))
    private val saveCapture = AtomicBoolean(false)
    private var screenW = 0
    private var screenH = 0
    private var frameW = 0
    private var gridW = 0
    private var gridH = 0

    // 경로 (메인 스레드)
    @Volatile private var stations: List<Station> = emptyList()
    private var nav: NavModel? = null
    private var route: List<RouteStep> = emptyList()
    private var routeOrderId = -1
    private var step = 0
    private var nearSinceMs = 0L

    private val mapListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadMap() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root != null) return START_NOT_STICKY
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            toast("다른 앱 위에 표시 권한이 필요합니다")
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WindowManager::class.java)
        store = MapStore(this)
        reloadMap()
        store.registerListener(mapListener)

        showRouteView()
        showPanel()
        startCapture(intent)
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (::store.isInitialized) store.unregisterListener(mapListener)
        root?.let { runCatching { wm.removeView(it) } }
        routeView?.let { runCatching { wm.removeView(it) } }
        root = null
        routeView = null
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }

    // ---------- 캡처·추적 ----------

    private fun startCapture(intent: Intent?) {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (mp == null) {
            routeText.text = "화면 캡처 없이 실행 중 · 경로 안내를 쓰려면 앱에서 다시 여세요"
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        // 게임이 가로 화면이므로 가상 디스플레이도 가로로 만든다
        val realW: Int
        val realH: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            realW = b.width(); realH = b.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            realW = p.x; realH = p.y
        }
        screenW = maxOf(realW, realH)
        screenH = minOf(realW, realH)
        frameW = (screenW / 2) and 1.inv()
        val frameH = (screenH / 2) and 1.inv()
        gridW = frameW / MapStore.CELL
        gridH = frameH / MapStore.CELL

        val worker = HandlerThread("guide-track").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)
        val reader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "zombie-guide", frameW, frameH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val src = RgbaBufferSource(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            if (saveCapture.compareAndSet(true, false)) saveCaptureFrame(src)
            tracker.update(GrayImage.from(src, MapStore.CELL))
            val cx = tracker.camX
            val cy = tracker.camY
            val matched = tracker.matched
            main.post { onTracked(cx, cy, matched) }
        } finally {
            image.close()
        }
    }

    private fun saveCaptureFrame(src: RgbaBufferSource) {
        val px = IntArray(src.width * src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) px[y * src.width + x] = src.rgb(x, y) or (0xFF shl 24)
        val bmp = Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(store.captureImage).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        store.saveCaptureMeta(MapStore.CaptureMeta(tracker.camX, tracker.camY, MapStore.CELL))
        main.post {
            routeView?.visibility = View.VISIBLE
            toast("지도 학습용 화면을 저장했습니다. 앱의 '지도 등록'에서 작업대를 누르세요")
        }
    }

    private fun reloadMap() {
        val m = store.load()
        stations = m.stations
        nav = NavGrid.build(m.stations, m.obstacles)?.let { NavModel(it, m.stations) }
        tracker.landmarks = m.landmarks
        tracker.charFx = m.charFx
        tracker.charFy = m.charFy
        routeOrderId = -1 // 경로 다시 계산
    }

    // ---------- 경로 (메인 스레드) ----------

    private fun onTracked(camX: Float, camY: Float, matched: Int) {
        val rv = routeView ?: return
        val st = stations
        if (st.isEmpty()) {
            routeText.text = "경로: 지도 없음 · 게임 화면에서 '지도학습' → 앱의 '지도 등록'"
            rv.show(emptyList())
            return
        }
        val order = orders.firstOrNull()
        if (order == null) {
            routeText.text = "경로: 주문을 추가하면 동선을 그립니다 · 위치 보정 ${matched}곳"
            rv.show(emptyList())
            return
        }
        val charCellX = tracker.charFx * gridW
        val charCellY = tracker.charFy * gridH
        if (order.id != routeOrderId) {
            val n = nav
            route = RoutePlanner.plan(order, st, charCellX + camX, charCellY + camY, n?.let { m -> { x, y, s -> m.distance(x, y, s) } })
            routeOrderId = order.id
            step = 0
        }
        if (step >= route.size) {
            routeText.text = "경로 완료 · 주문을 눌러 지우면 다음 주문 동선을 그립니다"
            rv.show(emptyList())
            return
        }

        val cur = route[step]
        val target = cur.station
        if (target == null) {
            routeText.text = "경로 ${step + 1}/${route.size}: ${cur.action} · ${cur.missing?.label} 위치를 지도에 등록하세요"
        } else {
            routeText.text = "경로 ${step + 1}/${route.size}: ${target.name} · ${cur.action}" +
                if (matched == 0 && tracker.lostFrames > 30) " · 위치 확인 중" else ""
            // 도착 판정: 캐릭터가 목표 근처에 0.35초 머물면 다음 지점으로
            val dist = hypot(target.x - camX - charCellX, target.y - camY - charCellY)
            val now = SystemClock.uptimeMillis()
            if (dist < ARRIVE_CELLS) {
                if (nearSinceMs == 0L) nearSinceMs = now
                if (now - nearSinceMs > ARRIVE_MS) {
                    step++
                    nearSinceMs = 0L
                }
            } else {
                nearSinceMs = 0L
            }
        }

        // 지도 좌표 → 화면 픽셀
        val scale = screenW.toFloat() / frameW * MapStore.CELL
        fun scr(p: Pair<Float, Float>) = (p.first - camX) * scale to (p.second - camY) * scale
        val n = nav
        val legs = ArrayList<RouteOverlayView.Leg>()
        var fx = charCellX + camX
        var fy = charCellY + camY
        for (s in route.drop(step).take(4)) {
            val stn = s.station ?: continue
            val pts = n?.path(fx, fy, stn) ?: listOf(fx to fy, stn.x to stn.y)
            legs += RouteOverlayView.Leg(pts.map { scr(it) }, "${stn.name}: ${s.action}")
            // 다음 구간은 이 작업대 앞(경로의 마지막 빈칸)에서 출발
            val stand = if (pts.size >= 2) pts[pts.size - 2] else stn.x to stn.y
            fx = stand.first
            fy = stand.second
        }
        rv.show(legs)
    }

    // ---------- 화면 구성 ----------

    private fun showRouteView() {
        val v = RouteOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        routeView = v
    }

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
        routeText = text(12f, bold = true).apply { setTextColor(Color.rgb(255, 214, 0)) }
        panel.addView(routeText)

        orderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(orderList)
        totals = text(12f, bold = false).apply {
            setTextColor(Color.rgb(255, 214, 120))
            setPadding(0, dp(4), 0, 0)
        }
        panel.addView(totals)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row1.addView(button("+주문", Color.rgb(20, 120, 70)) { openPicker() })
        row1.addView(button("◀", Color.DKGRAY) { if (step > 0) step-- })
        row1.addView(button("▶", Color.DKGRAY) { if (step < route.size) step++ })
        row1.addView(button("접기", Color.DKGRAY) { collapsed = !collapsed; refresh() })
        panel.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row2.addView(button("지도학습", Color.rgb(40, 80, 140)) { requestCapture() })
        row2.addView(button("비우기", Color.DKGRAY) { orders.clear(); refresh() })
        row2.addView(button("닫기", Color.rgb(170, 40, 40)) { stopSelf() })
        panel.addView(row2)

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
            y = dp(70)
        }
        enableDrag(panel, header)
        wm.addView(panel, params)
        root = panel
        routeText.text = "경로: 준비 중"
        refresh()
    }

    private fun requestCapture() {
        if (projection == null) {
            toast("화면 캡처가 꺼져 있습니다. 앱에서 가이드를 다시 여세요")
            return
        }
        // 경로 선이 학습 화면에 찍히지 않게 잠시 숨긴다
        routeView?.visibility = View.INVISIBLE
        main.postDelayed({ saveCapture.set(true) }, 250)
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
            columnCount = 6
            setPadding(0, dp(4), 0, 0)
        }
        Recipes.figures.forEach { f ->
            grid.addView(TextView(this).apply {
                text = f.short
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(6), dp(3), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(45, 60, 80))
                    cornerRadius = dp(5).toFloat()
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(54)
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
            toast("같은 피규어 두 개는 폴라로이드가 되어 쓸모없습니다")
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
        maxWidth = dp(330)
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
                    params.x = startX - (e.rawX - touchX).toInt() // gravity END
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, params)
                }
            }
            true
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "크익2 가이드", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("크익2 가이드 표시 중")
            .setContentText("게임 화면을 기기 안에서만 분석합니다")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .addAction(Notification.Action.Builder(null, "닫기", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "guide"
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "kr.stairvision.GUIDE_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ARRIVE_CELLS = 16f
        private const val ARRIVE_MS = 350L
        private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧")

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, GuideService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuideService::class.java))
        }
    }
}
