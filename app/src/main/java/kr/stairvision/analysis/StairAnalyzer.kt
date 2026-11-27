package kr.stairvision.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AnalyzerParams(
    /** 채널별 허용 색 차이(0..255). */
    val colorTolerance: Int = 40,
    /** 영역 안에서 발판 색 픽셀 비율이 이 값 이상이면 발판이 있다고 본다. */
    val matchThreshold: Float = 0.6f,
    /** 검사 영역 크기: 한 칸 간격 대비 반폭·반높이 비율. */
    val patchRatio: Float = 0.3f,
    /** 몇 픽셀마다 하나씩 검사할지. 2면 1/4만 읽는다. */
    val sampleStep: Int = 2,
)

/**
 * 고정 기하 기반 발판 분석기.
 *
 * 무한의 계단은 카메라가 캐릭터를 따라가므로 캐릭터와 현재 발판이 화면의 거의 같은 위치에 있다.
 * 다음 발판은 현재 발판에서 한 칸 위·한 칸 왼쪽 또는 오른쪽에만 올 수 있다.
 * 그래서 세 영역(현재, 왼쪽 위, 오른쪽 위)만 기준 색과 비교한다.
 */
class StairAnalyzer(@Volatile var params: AnalyzerParams = AnalyzerParams()) {

    fun analyze(src: PixelSource, cal: Calibration): Analysis {
        val w = src.width
        val h = src.height
        val cx = cal.currentStep.x * w
        val cy = cal.currentStep.y * h
        val dxPx = cal.dx * w
        val dyPx = cal.dy * h
        val halfW = max(2f, dxPx * params.patchRatio)
        val halfH = max(2f, dyPx * params.patchRatio)

        val cur = score(src, cx, cy, halfW, halfH, cal.stepColor)
        val left = score(src, cx - dxPx, cy - dyPx, halfW, halfH, cal.stepColor)
        val right = score(src, cx + dxPx, cy - dyPx, halfW, halfH, cal.stepColor)

        val th = params.matchThreshold
        val currentFound = cur >= th
        val side = when {
            !currentFound -> null
            left >= th && right < th -> Side.LEFT
            right >= th && left < th -> Side.RIGHT
            else -> null // 양쪽 모두 있거나 없음: 판단 보류
        }
        val confidence = if (side == null) 0f else min(cur, abs(left - right))
        return Analysis(cur, left, right, currentFound, side, confidence)
    }

    /** 사각 영역에서 기준 색과 가까운 픽셀 비율. 화면 밖 픽셀은 불일치로 센다. */
    fun score(
        src: PixelSource, cx: Float, cy: Float, halfW: Float, halfH: Float, ref: Int,
    ): Float {
        val step = max(1, params.sampleStep)
        val x0 = (cx - halfW).roundToInt()
        val x1 = (cx + halfW).roundToInt()
        val y0 = (cy - halfH).roundToInt()
        val y1 = (cy + halfH).roundToInt()
        var total = 0
        var hit = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                total++
                if (x in 0 until src.width && y in 0 until src.height &&
                    colorClose(src.rgb(x, y), ref, params.colorTolerance)
                ) hit++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    companion object {
        fun colorClose(a: Int, b: Int, tol: Int): Boolean {
            val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            return dr <= tol && dg <= tol && db <= tol
        }

        /** 보정용: 영역의 채널별 중앙값 색. 캐릭터가 일부 가려도 덜 흔들린다. */
        fun medianColor(src: PixelSource, cx: Float, cy: Float, halfW: Float, halfH: Float): Int {
            val rs = ArrayList<Int>()
            val gs = ArrayList<Int>()
            val bs = ArrayList<Int>()
            for (y in (cy - halfH).roundToInt()..(cy + halfH).roundToInt()) {
                for (x in (cx - halfW).roundToInt()..(cx + halfW).roundToInt()) {
                    if (x !in 0 until src.width || y !in 0 until src.height) continue
                    val c = src.rgb(x, y)
                    rs += (c shr 16) and 0xFF
                    gs += (c shr 8) and 0xFF
                    bs += c and 0xFF
                }
            }
            if (rs.isEmpty()) return 0
            rs.sort(); gs.sort(); bs.sort()
            val m = rs.size / 2
            return (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
        }
    }
}
