package kr.stairvision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 탭 전달 전용 접근성 서비스. 화면 내용은 읽지 않는다(canRetrieveWindowContent=false).
 * 탭은 CaptureService가 자동 입력을 켠 상태에서만 요청한다.
 */
class InputService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** 화면 픽셀 좌표에 짧은 탭을 보낸다. 전달 요청이 거부되면 false. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_MS = 30L

        @Volatile
        var instance: InputService? = null
            private set
    }
}
