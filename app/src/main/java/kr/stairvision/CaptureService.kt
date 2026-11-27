package kr.stairvision

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
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.view.WindowManager
import android.widget.Toast
import kr.stairvision.analysis.Action
import kr.stairvision.analysis.Calibration
import kr.stairvision.analysis.DecisionGate
import kr.stairvision.analysis.GateResult
import kr.stairvision.analysis.RgbaBufferSource
import kr.stairvision.analysis.StairAnalyzer
import kr.stairvision.analysis.label
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 화면 캡처 → 분석 → 판단 → 오버레이 → (자동 입력) 파이프라인.
 * 프레임은 기기 밖으로 나가지 않는다. 보정용 프레임 한 장만 앱 내부 저장소에 저장한다.
 */
class CaptureService : Service(), OverlayController.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var overlay: OverlayController? = null

    private val analyzer = StairAnalyzer()
    private val gate = DecisionGate()

    @Volatile private var calibration: Calibration? = null
    @Volatile private var autoEnabled = false
    @Volatile private var lastMessage = ""
    private val saveFrameRequested = AtomicBoolean(false)

    private var screenW = 0
    private var screenH = 0
    private var inputCount = 0
    private var lastOverlayNs = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadSettings()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14+: getMediaProjection 전에 mediaProjection 유형으로 포그라운드 시작해야 한다.
        startInForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = data?.let { mpm.getMediaProjection(code, it) }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        prefs = Prefs(this)

        val worker = HandlerThread("stair-analysis").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)

        // 콜백 등록은 createVirtualDisplay 전에 필수. 화면 잠금·상태바 중지 시 onStop이 온다.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val real = realScreenSize()
        screenW = real.x
        screenH = real.y
        // 분석은 절반 해상도로 충분하다. 좌표는 정규화해 쓰므로 입력 좌표에 영향 없음.
        val fw = (screenW / 2) and 1.inv()
        val fh = (screenH / 2) and 1.inv()
        val reader = ImageReader.newInstance(fw, fh, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "stair-vision", fw, fh, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )

        reloadSettings()
        gate.reset(prefs.initialFacing)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)

        overlay = OverlayController(this, this).also {
            if (!it.show()) toast("다른 앱 위에 표시 권한이 없어 오버레이를 띄우지 못했습니다")
        }
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        autoEnabled = false
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        overlay?.hide()
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        super.onDestroy()
    }

    // ---------- 프레임 처리 (분석 스레드) ----------

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val t0 = System.nanoTime()
            val plane = image.planes[0]
            val src = RgbaBufferSource(
                plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
            )
            if (saveFrameRequested.compareAndSet(true, false)) saveCalibrationFrame(src)

            val cal = calibration
            if (cal == null) {
                postOverlay(
                    OverlayState(
                        "보정 필요",
                        "게임 화면에서 '보정캡처' →",
                        "앱의 ④ 보정에서 4곳을 지정하세요",
                        false,
                    ),
                    force = false,
                )
                return
            }

            val a = analyzer.analyze(src, cal)
            val now = System.nanoTime()
            // 프레임 촬영 시각. 시계 기준이 다르면 도착 시각으로 대신한다.
            val frameNs = image.timestamp.let { if (abs(it - now) < 1_000_000_000L) it else now }

            var force = false
            when (val r = gate.onFrame(frameNs, now, a, autoEnabled)) {
                is GateResult.Fire -> {
                    force = true
                    if (!fire(r.action, cal)) haltAuto("입력 전달 실패 (접근성 서비스 확인)")
                }
                is GateResult.Halt -> {
                    force = true
                    haltAuto(r.reason)
                    gate.reset(prefs.initialFacing) // 다음 판은 시작 방향부터
                }
                else -> Unit
            }

            val action = gate.displayAction(a)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            postOverlay(
                OverlayState(
                    headline = "${arrow(action)} ${action.label()}",
                    detail = String.format(
                        Locale.US, "다음:%s 방향:%s 신뢰도 %.2f\nL %.2f  R %.2f  현재 %.2f",
                        a.nextSide?.label() ?: "?", gate.facing.label(), a.confidence,
                        a.leftScore, a.rightScore, a.currentScore,
                    ),
                    status = String.format(
                        Locale.US, "%s · 입력 %d회 · %.1fms%s",
                        if (autoEnabled) "자동 입력 ON" else "분석만",
                        inputCount, ms,
                        if (lastMessage.isNotEmpty()) "\n$lastMessage" else "",
                    ),
                    autoOn = autoEnabled,
                ),
                force,
            )
        } finally {
            image.close()
        }
    }

    private fun fire(action: Action, cal: Calibration): Boolean {
        val target = if (action == Action.CLIMB) cal.climbButton else cal.turnButton
        val svc = InputService.instance ?: return false
        val ok = svc.tap(target.x * screenW, target.y * screenH)
        if (ok) inputCount++
        return ok
    }

    private fun haltAuto(reason: String) {
        if (!autoEnabled) return
        autoEnabled = false
        lastMessage = "자동 입력 중단: $reason"
        main.post { toast(lastMessage) }
    }

    private fun saveCalibrationFrame(src: RgbaBufferSource) {
        val px = IntArray(src.width * src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            px[y * src.width + x] = src.rgb(x, y) or (0xFF shl 24)
        }
        val bmp = Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(filesDir, Prefs.CALIBRATION_FRAME)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        main.post { toast("보정용 화면을 저장했습니다. 앱의 ④ 보정에서 지정하세요") }
    }

    private fun postOverlay(state: OverlayState, force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastOverlayNs < 100_000_000L) return
        lastOverlayNs = now
        main.post { overlay?.update(state) }
    }

    private fun reloadSettings() {
        calibration = prefs.calibration()
        analyzer.params = prefs.analyzerParams()
        gate.config = prefs.gateConfig()
    }

    // ---------- 오버레이 버튼 (메인 스레드) ----------

    override fun onToggleAuto() {
        if (autoEnabled) {
            autoEnabled = false
            lastMessage = "사용자가 자동 입력을 껐습니다"
            return
        }
        when {
            calibration == null -> toast("먼저 보정을 완료하세요")
            InputService.instance == null -> toast("접근성 설정에서 '계단 Vision 자동 입력'을 켜세요")
            else -> {
                lastMessage = ""
                autoEnabled = true
                toast("자동 입력 ON · 현재 방향: ${gate.facing.label()} (다르면 '방향↔')")
            }
        }
    }

    override fun onFlipFacing() {
        gate.facing = gate.facing.flip()
        toast("현재 방향: ${gate.facing.label()}")
    }

    override fun onCaptureCalibration() {
        saveFrameRequested.set(true)
    }

    override fun onEmergencyStop() {
        autoEnabled = false
        stopSelf()
    }

    // ---------- 보조 ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "화면 분석", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("계단 Vision 분석 중")
            .setContentText("게임 화면을 기기 안에서만 분석합니다")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun realScreenSize(): Point {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }
        }
    }

    private fun arrow(a: Action) = when (a) {
        Action.CLIMB -> "↑"
        Action.TURN_LEFT -> "↖"
        Action.TURN_RIGHT -> "↗"
        Action.UNKNOWN -> "…"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "kr.stairvision.START"
        private const val ACTION_STOP = "kr.stairvision.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }
}
