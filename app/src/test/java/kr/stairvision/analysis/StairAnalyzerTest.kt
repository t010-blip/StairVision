package kr.stairvision.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 합성 화면: 200x400, 한 칸 = 가로 30px · 세로 20px, 현재 발판 중심 (100, 300). */
class StairAnalyzerTest {
    private val w = 200
    private val h = 400
    private val bg = 0x202040
    private val step = 0xC08040

    private val cal = Calibration(
        currentStep = NPoint(100f / w, 300f / h),
        nextStep = NPoint(70f / w, 280f / h),
        climbButton = NPoint(0.8f, 0.9f),
        turnButton = NPoint(0.2f, 0.9f),
        stepColor = step,
    )

    private fun image(vararg centers: Pair<Int, Int>, color: Int = step): IntArraySource {
        val px = IntArray(w * h) { bg }
        for ((cx, cy) in centers) {
            for (y in cy - 10 until cy + 10) for (x in cx - 15 until cx + 15) {
                if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            }
        }
        return IntArraySource(px, w, h)
    }

    private val analyzer = StairAnalyzer()

    @Test fun nextStepOnLeft() {
        val a = analyzer.analyze(image(100 to 300, 70 to 280), cal)
        assertTrue(a.currentFound)
        assertEquals(Side.LEFT, a.nextSide)
        assertTrue(a.confidence > 0.9f)
    }

    @Test fun nextStepOnRight() {
        val a = analyzer.analyze(image(100 to 300, 130 to 280), cal)
        assertEquals(Side.RIGHT, a.nextSide)
    }

    @Test fun calibrationWithRightSampleGivesSameGeometry() {
        val c = cal.copy(nextStep = NPoint(130f / w, 280f / h))
        val a = analyzer.analyze(image(100 to 300, 70 to 280), c)
        assertEquals(Side.LEFT, a.nextSide)
    }

    @Test fun missingNextStepIsUnknownNotTurn() {
        val a = analyzer.analyze(image(100 to 300), cal)
        assertNull(a.nextSide)
        assertEquals(Action.UNKNOWN, decide(a.nextSide, Side.LEFT))
    }

    @Test fun bothSidesIsUnknown() {
        val a = analyzer.analyze(image(100 to 300, 70 to 280, 130 to 280), cal)
        assertNull(a.nextSide)
    }

    @Test fun missingCurrentStepIsUnknown() {
        val a = analyzer.analyze(image(70 to 280), cal)
        assertFalse(a.currentFound)
        assertNull(a.nextSide)
    }

    @Test fun slightlyDifferentShadeStillMatches() {
        val a = analyzer.analyze(image(100 to 300, 130 to 280, color = 0xB07A50), cal)
        assertEquals(Side.RIGHT, a.nextSide)
    }

    @Test fun decisionTable() {
        assertEquals(Action.CLIMB, decide(Side.LEFT, Side.LEFT))
        assertEquals(Action.TURN_RIGHT, decide(Side.RIGHT, Side.LEFT))
        assertEquals(Action.CLIMB, decide(Side.RIGHT, Side.RIGHT))
        assertEquals(Action.TURN_LEFT, decide(Side.LEFT, Side.RIGHT))
        assertEquals(Action.UNKNOWN, decide(null, Side.RIGHT))
    }

    @Test fun medianColorIgnoresPartialOcclusion() {
        val src = image(100 to 300)
        val c = StairAnalyzer.medianColor(src, 100f, 300f, 9f, 6f)
        assertEquals(step, c)
    }
}
