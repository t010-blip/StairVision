package kr.stairvision.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class AnalyzerParams(
    /** 채널별 허용 색 차이(0..255). */
    val colorTolerance: Int = 40,
    /** 발판이 있는 쪽 점수의 최소값. */
    val matchThreshold: Float = 0.5f,
    /** 있는 쪽과 없는 쪽 점수의 최소 차이. */
    val minMargin: Float = 0.25f,
    /** 검사 영역 크기: 한 칸 간격 대비 반폭·반높이 비율. */
    val patchRatio: Float = 0.3f,
    /** 몇 픽셀마다 하나씩 검사할지. */
    val sampleStep: Int = 2,
    /** 현재 발판 색 구성에 넣을 색의 최소 비율. */
    val paletteMinShare: Float = 0.12f,
)

/**
 * 고정 기하 + 상대 비교 기반 발판 분석기.
 *
 * 1. 캐릭터가 서 있는 현재 발판의 색 구성(최대 4색)을 매 프레임 새로 읽는다.
 *    → 계단 스킨·테마가 바뀌어도 기준이 따라간다.
 * 2. 위 줄(다음 발판 후보 2곳)과 아래 줄(이전 발판 후보 2곳)을 그 색 구성과 비교한다.
 *    각 줄에는 발판이 정확히 하나만 있으므로, 두 후보의 '차이'로 어느 쪽인지 정한다.
 * 3. 각 후보는 위·중간·아래 세 띠로 나눠 두 번째로 높은 띠 점수를 쓴다.
 *    → 아이템·오브젝트가 한 띠를 가려도 판단이 유지된다.
 * 4. 캐릭터는 이전 발판에서 바라보는 방향으로 왔으므로, 이전 발판의 반대쪽이 현재 방향이다.
 */
class StairAnalyzer(@Volatile var params: AnalyzerParams = AnalyzerParams()) {

    fun analyze(src: PixelSource, cal: Calibration): Analysis {
        val p = params
        val w = src.width
        val h = src.height
        val cx = cal.currentStep.x * w
        val cy = cal.currentStep.y * h
        val dxPx = cal.dx * w
        val dyPx = cal.dy * h
        val halfW = max(2f, dxPx * p.patchRatio)
        val halfH = max(3f, dyPx * p.patchRatio)

        val palette = palette(src, cx, cy, halfW, halfH).ifEmpty { listOf(cal.stepColor) }

        val cur = bandScore(src, cx, cy, halfW, halfH, palette)
        val upL = bandScore(src, cx - dxPx, cy - dyPx, halfW, halfH, palette)
        val upR = bandScore(src, cx + dxPx, cy - dyPx, halfW, halfH, palette)
        val downL = bandScore(src, cx - dxPx, cy + dyPx, halfW, halfH, palette)
        val downR = bandScore(src, cx + dxPx, cy + dyPx, halfW, halfH, palette)

        val next = pick(upL, upR)
        val prev = pick(downL, downR)
        val confidence = if (next == null) 0f else abs(upL - upR)
        return Analysis(
            currentScore = cur,
            leftScore = upL,
            rightScore = upR,
            currentFound = cur >= p.matchThreshold,
            nextSide = next,
            confidence = confidence,
            prevSide = prev,
            belowLeftScore = downL,
            belowRightScore = downR,
            paletteSize = palette.size,
        )
    }

    /** 두 후보 중 한쪽만 확실히 발판일 때 그쪽. 아니면 null(보류). */
    private fun pick(left: Float, right: Float): Side? {
        val hi = max(left, right)
        val lo = minOf(left, right)
        if (hi < params.matchThreshold || hi - lo < params.minMargin) return null
        return if (left > right) Side.LEFT else Side.RIGHT
    }

    /** 영역을 위·중간·아래 세 띠로 나눠 각 띠의 일치 비율을 구하고, 두 번째로 높은 값을 쓴다. */
    fun bandScore(
        src: PixelSource, cx: Float, cy: Float, halfW: Float, halfH: Float, palette: List<Int>,
    ): Float {
        val bandH = 2f * halfH / 3f
        val top = cy - halfH
        val scores = FloatArray(3) { i ->
            val by0 = top + i * bandH
            fraction(src, cx - halfW, cx + halfW, by0, by0 + bandH, palette)
        }
        scores.sort()
        return scores[1]
    }

    private fun fraction(
        src: PixelSource, fx0: Float, fx1: Float, fy0: Float, fy1: Float, palette: List<Int>,
    ): Float {
        val step = max(1, params.sampleStep)
        val x0 = fx0.roundToInt()
        val x1 = fx1.roundToInt()
        val y0 = fy0.roundToInt()
        val y1 = max(y0, fy1.roundToInt() - 1)
        var total = 0
        var hit = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                total++
                if (x in 0 until src.width && y in 0 until src.height &&
                    matchesAny(src.rgb(x, y), palette, params.colorTolerance)
                ) hit++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    /**
     * 현재 발판 영역의 주요 색(최대 4개). 채널당 16단계로 묶어 세고,
     * paletteMinShare 이상 차지하는 묶음의 평균색을 쓴다.
     */
    fun palette(src: PixelSource, cx: Float, cy: Float, halfW: Float, halfH: Float): List<Int> {
        val step = max(1, params.sampleStep)
        val buckets = HashMap<Int, IntArray>()
        var total = 0
        var y = (cy - halfH).roundToInt()
        val y1 = (cy + halfH).roundToInt()
        while (y <= y1) {
            var x = (cx - halfW).roundToInt()
            val x1 = (cx + halfW).roundToInt()
            while (x <= x1) {
                if (x in 0 until src.width && y in 0 until src.height) {
                    val c = src.rgb(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    val acc = buckets.getOrPut(key) { IntArray(4) }
                    acc[0]++; acc[1] += r; acc[2] += g; acc[3] += b
                    total++
                }
                x += step
            }
            y += step
        }
        if (total == 0) return emptyList()
        return buckets.values
            .filter { it[0] >= total * params.paletteMinShare }
            .sortedByDescending { it[0] }
            .take(4)
            .map { a -> ((a[1] / a[0]) shl 16) or ((a[2] / a[0]) shl 8) or (a[3] / a[0]) }
    }

    companion object {
        fun colorClose(a: Int, b: Int, tol: Int): Boolean {
            val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            return dr <= tol && dg <= tol && db <= tol
        }

        fun matchesAny(c: Int, palette: List<Int>, tol: Int): Boolean {
            for (p in palette) if (colorClose(c, p, tol)) return true
            return false
        }

        /** 보정용: 영역의 채널별 중앙값 색. */
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
