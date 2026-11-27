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
