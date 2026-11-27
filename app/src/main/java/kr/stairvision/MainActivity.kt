package kr.stairvision

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.stairvision.analysis.Side

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var facingGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        fun mark(ok: Boolean) = if (ok) "✅" else "⬜"
        status.text = buildString {
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} 다른 앱 위에 표시")
            appendLine("${mark(InputService.instance != null)} 자동 입력(접근성) — 분석만 할 때는 불필요")
            appendLine("${mark(CaptureService.isRunning)} 화면 분석 실행 중")
            append("${mark(prefs.calibration() != null)} 보정 완료")
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        col.addView(TextView(this).apply {
            text = "무한의 계단 Vision Analyzer · 프로토타입"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)); textSize = 14f }
        col.addView(status)

        col.addView(button("① 다른 앱 위에 표시 권한") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        })
        col.addView(button("② 자동 입력 권한 (접근성)") { showAccessibilityDisclosure() })
        col.addView(button("③ 분석 시작 (화면 캡처)") { startCapture() })
        col.addView(button("④ 보정") {
            startActivity(Intent(this, CalibrationActivity::class.java))
        })
        col.addView(button("분석 중지") {
            CaptureService.stop(this)
            status.postDelayed({ refreshStatus() }, 300)
        })

        col.addView(header("설정"))
        col.addView(TextView(this).apply { text = "게임 시작 시 캐릭터 방향" })
        facingGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { id = ID_LEFT; text = "왼쪽" })
            addView(RadioButton(context).apply { id = ID_RIGHT; text = "오른쪽" })
            check(if (prefs.initialFacing == Side.LEFT) ID_LEFT else ID_RIGHT)
        }
        col.addView(facingGroup)
        col.addView(field("settle", "입력 후 대기 (ms)", prefs.settleMs.toString(), decimal = false))
        col.addView(field("frames", "연속 일치 프레임 수", prefs.requiredFrames.toString(), decimal = false))
        col.addView(field("threshold", "발판 판정 비율 (0~1)", prefs.matchThreshold.toString(), decimal = true))
        col.addView(field("tolerance", "색 허용 오차 (0~255)", prefs.colorTolerance.toString(), decimal = false))
        col.addView(field("confidence", "최소 신뢰도 (0~1)", prefs.minConfidence.toString(), decimal = true))
        col.addView(button("설정 저장") { saveSettings() })

        col.addView(header("사용 순서"))
        col.addView(TextView(this).apply {
            text = """
                1. ①로 오버레이 권한을 켭니다. 자동 입력을 쓸 때만 ②도 켭니다.
                2. ③ 분석 시작 → '전체 화면' 공유를 고릅니다.
                3. 게임을 열고 캐릭터가 계단 위에 선 화면에서 오버레이의 '보정캡처'를 누릅니다.
                4. 이 앱으로 돌아와 ④ 보정에서 현재 발판 → 다음 발판 → 오르기 버튼 → 방향 전환 버튼을 차례로 누르고 저장합니다.
                5. 게임으로 돌아가 오버레이 판단이 맞는지 먼저 확인합니다.
                6. 자동 입력은 오프라인 단독 플레이에서만 시험하세요. '자동' 버튼으로 켜고, '■ 중지'로 즉시 멈춥니다.

                오버레이 패널은 글자를 끌어 옮길 수 있습니다. 계단과 게임 버튼을 가리지 않는 곳에 두세요.
            """.trimIndent()
            textSize = 13f
        })
        return ScrollView(this).apply { addView(col) }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast("먼저 ① 다른 앱 위에 표시 권한을 켜세요")
            return
        }
        if (CaptureService.isRunning) {
            toast("이미 분석 중입니다")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // 앱 단위 공유가 아닌 전체 화면을 받아야 탭 좌표와 캡처 좌표가 일치한다.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Activity result API는 androidx 의존성을 줄이려고 쓰지 않았다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            status.postDelayed({ refreshStatus() }, 500)
            toast("분석을 시작했습니다. 게임으로 이동하세요")
        } else {
            toast("화면 캡처가 허용되지 않았습니다")
        }
    }

    /** Play 접근성 정책의 '명시적 고지와 동의'에 해당하는 화면. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("자동 입력 권한 안내")
            .setMessage(
                "이 앱은 접근성 서비스를 '보정에서 지정한 오르기·방향 전환 버튼 위치에 탭을 보내는 용도'로만 사용합니다.\n\n" +
                    "• 다른 앱의 화면 내용을 읽지 않습니다.\n" +
                    "• 어떤 데이터도 수집하거나 외부로 보내지 않습니다.\n" +
                    "• 오버레이의 '자동' 버튼을 켠 동안에만 탭을 보내며, '■ 중지'로 즉시 멈춥니다.\n\n" +
                    "게임 이용약관이 자동 입력을 금지할 수 있습니다. 경쟁 모드와 랭킹에서는 사용하지 마세요.",
            )
            .setPositiveButton("동의하고 설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveSettings() {
        fun long(k: String, d: Long) = fields[k]?.text?.toString()?.toLongOrNull() ?: d
        fun float(k: String, d: Float) = fields[k]?.text?.toString()?.toFloatOrNull() ?: d
        prefs.initialFacing = if (facingGroup.checkedRadioButtonId == ID_RIGHT) Side.RIGHT else Side.LEFT
        prefs.settleMs = long("settle", 90).coerceIn(0, 1000)
        prefs.requiredFrames = long("frames", 2).toInt().coerceIn(1, 10)
        prefs.matchThreshold = float("threshold", 0.6f).coerceIn(0.1f, 1f)
        prefs.colorTolerance = long("tolerance", 40).toInt().coerceIn(0, 255)
        prefs.minConfidence = float("confidence", 0.4f).coerceIn(0f, 1f)
        toast("저장했습니다. 분석 중이면 바로 적용됩니다")
    }

    private fun field(key: String, label: String, value: String, decimal: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(this).apply {
            setText(value)
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fields[key] = et
        row.addView(et)
        return row
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        private const val ID_LEFT = 1001
        private const val ID_RIGHT = 1002
    }
}
