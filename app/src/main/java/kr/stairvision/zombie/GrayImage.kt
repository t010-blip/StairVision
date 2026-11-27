package kr.stairvision.zombie

import kr.stairvision.analysis.PixelSource
import kotlin.math.sqrt

/** 위치 추적용 축소 흑백 영상. 한 칸 = 원본 k×k 픽셀. 값은 0..255. */
class GrayImage(val w: Int, val h: Int, val px: IntArray) {

    operator fun get(x: Int, y: Int): Int = px[y * w + x]

    /** (cx, cy)를 중심으로 한 (2·half)² 조각. 영상 밖으로 나가면 null. */
    fun crop(cx: Int, cy: Int, half: Int): GrayImage? {
        val x0 = cx - half
        val y0 = cy - half
        val size = half * 2
        if (x0 < 0 || y0 < 0 || x0 + size > w || y0 + size > h) return null
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) out[y * size + x] = this[x0 + x, y0 + y]
        return GrayImage(size, size, out)
    }

    fun mean(): Float = px.sum().toFloat() / px.size

    fun std(): Float {
        val m = mean()
        var s = 0.0
        for (v in px) s += (v - m) * (v - m)
        return sqrt(s / px.size).toFloat()
    }

    companion object {
        /**
         * 원본의 (rx, ry, rw, rh) 영역을 outW×outH 칸으로 다시 표본 추출한다.
         * 캡처에 검은 여백이 있어도 게임 화면만 휴대폰 화면 비율로 펴서 쓴다.
         */
        fun fromRegion(src: PixelSource, rx: Int, ry: Int, rw: Int, rh: Int, outW: Int, outH: Int): GrayImage {
            val out = IntArray(outW * outH)
            for (y in 0 until outH) {
                val sy = (ry + ((y + 0.5f) * rh / outH).toInt()).coerceIn(0, src.height - 1)
                for (x in 0 until outW) {
                    val sx = (rx + ((x + 0.5f) * rw / outW).toInt()).coerceIn(0, src.width - 1)
                    val c = src.rgb(sx, sy)
                    out[y * outW + x] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                }
            }
            return GrayImage(outW, outH, out)
        }

        /**
         * 캡처 가장자리의 검은 여백(레터박스)을 찾아 실제 화면 영역 [x, y, w, h]을 돌려준다.
         * 여백은 좌우 또는 상하가 대칭일 때만 인정한다(게임 속 어두운 벽을 여백으로 착각하지 않도록).
         */
        fun contentBounds(src: PixelSource): IntArray {
            val w = src.width
            val h = src.height
            fun colBlack(x: Int): Boolean {
                var y = 0
                while (y < h) {
                    if (!dark(src.rgb(x, y))) return false
                    y += maxOf(1, h / 40)
                }
                return true
            }
            fun rowBlack(y: Int): Boolean {
                var x = 0
                while (x < w) {
                    if (!dark(src.rgb(x, y))) return false
                    x += maxOf(1, w / 60)
                }
                return true
            }
            var l = 0
            while (l < w * 45 / 100 && colBlack(l)) l++
            var r = 0
            while (r < w * 45 / 100 && colBlack(w - 1 - r)) r++
            var t = 0
            while (t < h * 45 / 100 && rowBlack(t)) t++
            var b = 0
            while (b < h * 45 / 100 && rowBlack(h - 1 - b)) b++
            val tolX = maxOf(2, w / 50)
            val tolY = maxOf(2, h / 50)
            val sideBars = l > tolX && kotlin.math.abs(l - r) <= tolX
            val topBars = t > tolY && kotlin.math.abs(t - b) <= tolY
            val x0 = if (sideBars) l else 0
            val x1 = if (sideBars) w - r else w
            val y0 = if (topBars) t else 0
            val y1 = if (topBars) h - b else h
            return intArrayOf(x0, y0, x1 - x0, y1 - y0)
        }

        private fun dark(c: Int): Boolean =
            ((c shr 16) and 0xFF) < 14 && ((c shr 8) and 0xFF) < 14 && (c and 0xFF) < 14

        fun from(src: PixelSource, k: Int): GrayImage {
            val w = src.width / k
            val h = src.height / k
            val out = IntArray(w * h)
            val o = k / 2
            for (y in 0 until h) for (x in 0 until w) {
                val c = src.rgb(x * k + o, y * k + o)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[y * w + x] = (r * 3 + g * 6 + b) / 10
            }
            return GrayImage(w, h, out)
        }
    }
}
