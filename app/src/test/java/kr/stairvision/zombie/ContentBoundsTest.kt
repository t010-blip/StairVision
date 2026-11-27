package kr.stairvision.zombie

import kr.stairvision.analysis.IntArraySource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentBoundsTest {
    private fun img(w: Int, h: Int, fill: (Int, Int) -> Int) = IntArraySource(IntArray(w * h) { fill(it % w, it / w) }, w, h)

    @Test fun pillarboxIsRemoved() {
        // 1170×540 캡처 안에 좌우 100px씩 검은 여백
        val src = img(1170, 540) { x, y -> if (x < 100 || x >= 1070) 0 else 0x806040 + (x % 7) }
        assertArrayEquals(intArrayOf(100, 0, 970, 540), GrayImage.contentBounds(src))
    }

    @Test fun darkAreaOnOneSideIsNotABar() {
        // 게임 속 어두운 벽이 왼쪽에만 있음 → 여백 아님
        val src = img(1170, 540) { x, _ -> if (x < 150) 0 else 0x806040 }
        assertArrayEquals(intArrayOf(0, 0, 1170, 540), GrayImage.contentBounds(src))
    }

    @Test fun regionIsResampledToScreenGrid() {
        val src = img(1170, 540) { x, _ -> if (x < 100 || x >= 1070) 0 else 200 shl 16 }
        val b = GrayImage.contentBounds(src)
        val g = GrayImage.fromRegion(src, b[0], b[1], b[2], b[3], 146, 67)
        assertEquals(146, g.w)
        assertEquals(60, g[0, 0]) // 여백(0)이 아니라 게임 영역 색(빨강 200 → 60)
    }
}
