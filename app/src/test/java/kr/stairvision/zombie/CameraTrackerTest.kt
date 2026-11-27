package kr.stairvision.zombie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CameraTrackerTest {
    /** 600×300칸짜리 가상 맵: 4×4칸 블록마다 다른 밝기(반복되지 않는 무늬). */
    private val worldW = 600
    private val worldH = 300
    private val world: IntArray = run {
        val rnd = Random(7)
        val blocks = IntArray((worldW / 4 + 1) * (worldH / 4 + 1)) { 40 + rnd.nextInt(180) }
        IntArray(worldW * worldH) { i ->
            val x = i % worldW
            val y = i / worldW
            blocks[(y / 4) * (worldW / 4 + 1) + x / 4]
        }
    }

    private val viewW = 146
    private val viewH = 67

    /** 카메라 (cx, cy)에서 본 화면. 화면 좌표 = 지도 좌표 − 카메라. */
    private fun view(cx: Int, cy: Int): GrayImage {
        val px = IntArray(viewW * viewH)
        for (y in 0 until viewH) for (x in 0 until viewW) px[y * viewW + x] = world[(cy + y) * worldW + (cx + x)]
        return GrayImage(viewW, viewH, px)
    }

    private fun landmark(id: Int, mx: Int, my: Int): Landmark {
        val img = view(0, 0)
        val worldImg = GrayImage(worldW, worldH, world)
        return Landmark(id, mx.toFloat(), my.toFloat(), worldImg.crop(mx, my, 8)!!).also { assertTrue(img.w > 0) }
    }

    @Test fun followsWalkingWithFrameDifferences() {
        val t = CameraTracker()
        var cx = 100
        var cy = 80
        t.reset(cx.toFloat(), cy.toFloat())
        t.update(view(cx, cy))
        // 오른쪽으로 3칸씩 20프레임, 아래로 2칸씩 10프레임
        repeat(20) { cx += 3; t.update(view(cx, cy)) }
        repeat(10) { cy += 2; t.update(view(cx, cy)) }
        assertEquals(cx.toFloat(), t.camX, 0.5f)
        assertEquals(cy.toFloat(), t.camY, 0.5f)
    }

    @Test fun landmarkCorrectsDrift() {
        val t = CameraTracker()
        t.landmarks = listOf(landmark(1, 230, 130), landmark(2, 300, 140))
        t.reset(205f, 105f) // 실제 카메라는 (200, 100): 5칸 틀린 상태
        t.update(view(200, 100))
        assertTrue(t.matched >= 1)
        assertEquals(200f, t.camX, 0.5f)
        assertEquals(100f, t.camY, 0.5f)
    }

    @Test fun relocalizesAfterTeleport() {
        val t = CameraTracker()
        t.landmarks = listOf(landmark(1, 430, 200))
        t.reset(0f, 0f)
        // 지형지물이 예상 위치와 전혀 다른 곳에 보이는 상황(재시작·순간 이동)
        repeat(8) { t.update(view(380, 170)) }
        assertEquals(380f, t.camX, 0.5f)
        assertEquals(170f, t.camY, 0.5f)
    }
}
