package kr.stairvision.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 합성 화면: 200x400, 한 칸 = 가로 30px · 세로 20px, 현재 발판 중심 (100, 300).
 * 다음 발판 후보: (70|130, 280), 이전 발판 후보: (70|130, 320).
 */
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

    private class Scene(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun rect(cx: Int, cy: Int, hw: Int, hh: Int, color: Int) = apply {
            for (y in cy - hh until cy + hh) for (x in cx - hw until cx + hw) {
                if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            }
        }
        fun step(cx: Int, cy: Int, color: Int) = rect(cx, cy, 15, 10, color)
        fun checker(cx: Int, cy: Int, a: Int, b: Int) = apply {
            for (y in cy - 10 until cy + 10) for (x in cx - 15 until cx + 15) {
                px[y * w + x] = if (((x / 3) + (y / 3)) % 2 == 0) a else b
            }
        }
        fun src() = IntArraySource(px, w, h)
    }

    private fun scene() = Scene(w, h, bg)
    private val analyzer = StairAnalyzer()

    @Test fun continuingDiagonalIsClimb() {
        // 오른쪽 아래에서 올라와 왼쪽을 보고 있고, 다음 발판도 왼쪽 위
        val s = scene().step(100, 300, step).step(130, 320, step).step(70, 280, step)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.LEFT, a.nextSide)
        assertEquals(Side.RIGHT, a.prevSide)
        assertEquals(Side.LEFT, a.inferredFacing)
        assertEquals(Action.CLIMB, decide(a.nextSide, a.inferredFacing!!))
    }

    @Test fun zigzagIsTurn() {
        // 오른쪽 아래에서 올라왔는데 다음 발판이 오른쪽 위
        val s = scene().step(100, 300, step).step(130, 320, step).step(130, 280, step)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.RIGHT, a.nextSide)
        assertEquals(Action.TURN_RIGHT, decide(a.nextSide, a.inferredFacing!!))
    }

    @Test fun newStairSkinStillWorks() {
        val skin = 0x40C0A0 // 보정 때와 전혀 다른 색
        val s = scene().step(100, 300, skin).step(70, 320, skin).step(130, 280, skin)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.RIGHT, a.nextSide)
        assertEquals(Side.LEFT, a.prevSide)
    }

    @Test fun texturedStairStillWorks() {
        val s = scene().checker(100, 300, 0xE0E0E0, 0x606060)
            .checker(70, 320, 0xE0E0E0, 0x606060)
            .checker(70, 280, 0xE0E0E0, 0x606060)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.LEFT, a.nextSide)
        assertTrue(a.paletteSize >= 2)
    }

    @Test fun objectOnNextStepStillDetected() {
        // 다음 발판 윗부분(검사 영역 위 띠)을 금색 아이템이 가림
        val s = scene().step(100, 300, step).step(130, 320, step).step(70, 280, step)
            .rect(70, 276, 12, 4, 0xFFD700)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.LEFT, a.nextSide)
    }

    @Test fun missingNextStepIsUnknownNotTurn() {
        val s = scene().step(100, 300, step).step(130, 320, step)
        val a = analyzer.analyze(s.src(), cal)
        assertNull(a.nextSide)
        assertEquals(Action.UNKNOWN, decide(a.nextSide, Side.LEFT))
    }

    @Test fun bothSidesIsUnknown() {
        val s = scene().step(100, 300, step).step(70, 280, step).step(130, 280, step)
        assertNull(analyzer.analyze(s.src(), cal).nextSide)
    }

    @Test fun gameStartWithoutPreviousStepFallsBack() {
        // 시작 바닥: 아래 줄이 전부 바닥색이라 이전 발판 판단 불가
        val s = scene().rect(100, 330, 100, 20, 0x808080).step(100, 300, step).step(70, 280, step)
        val a = analyzer.analyze(s.src(), cal)
        assertEquals(Side.LEFT, a.nextSide)
        assertNull(a.prevSide)
    }

    /** 현재 발판 대신 배경색을 학습해도 위·아래가 함께 뒤집혀 누를 버튼은 같다. */
    @Test fun invertedPaletteStillPressesSameButton() {
        val truth = scene().step(100, 300, step).step(130, 320, step).step(130, 280, step)
        val t = analyzer.analyze(truth.src(), cal)
        val wrong = scene().step(130, 320, step).step(130, 280, step) // 현재 발판이 가려져 배경만 보임
        val x = analyzer.analyze(wrong.src(), cal)
        val ta = decide(t.nextSide, t.inferredFacing!!)
        val xa = decide(x.nextSide, x.inferredFacing!!)
        assertEquals(ta.usesTurnButton(), xa.usesTurnButton())
    }

    @Test fun decisionTable() {
        assertEquals(Action.CLIMB, decide(Side.LEFT, Side.LEFT))
        assertEquals(Action.TURN_RIGHT, decide(Side.RIGHT, Side.LEFT))
        assertEquals(Action.CLIMB, decide(Side.RIGHT, Side.RIGHT))
        assertEquals(Action.TURN_LEFT, decide(Side.LEFT, Side.RIGHT))
        assertEquals(Action.UNKNOWN, decide(null, Side.RIGHT))
    }

    @Test fun medianColorOfStep() {
        val s = scene().step(100, 300, step)
        assertEquals(step, StairAnalyzer.medianColor(s.src(), 100f, 300f, 9f, 6f))
    }
}
