package kr.stairvision.analysis

import java.nio.ByteBuffer

/** 프레임 픽셀을 읽는 공통 인터페이스. 반환값은 0xRRGGBB. */
interface PixelSource {
    val width: Int
    val height: Int
    fun rgb(x: Int, y: Int): Int
}

/** ImageReader(RGBA_8888) 버퍼를 복사 없이 읽는다. 필요한 픽셀만 접근한다. */
class RgbaBufferSource(
    private val buffer: ByteBuffer,
    override val width: Int,
    override val height: Int,
    private val rowStride: Int,
    private val pixelStride: Int,
) : PixelSource {
    override fun rgb(x: Int, y: Int): Int {
        val i = y * rowStride + x * pixelStride
        val r = buffer.get(i).toInt() and 0xFF
        val g = buffer.get(i + 1).toInt() and 0xFF
        val b = buffer.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }
}

/** ARGB 정수 배열(Bitmap.getPixels 결과나 테스트 이미지). */
class IntArraySource(
    private val pixels: IntArray,
    override val width: Int,
    override val height: Int,
) : PixelSource {
    override fun rgb(x: Int, y: Int): Int = pixels[y * width + x] and 0xFFFFFF
}
