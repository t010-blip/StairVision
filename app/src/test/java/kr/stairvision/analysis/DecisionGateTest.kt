package kr.stairvision.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionGateTest {
    private val ms = 1_000_000L

    private fun seen(side: Side?, conf: Float = 0.9f) =
        Analysis(0.95f, 0f, 0f, currentFound = true, nextSide = side, confidence = if (side == null) 0f else conf)

    private val lost = Analysis(0f, 0f, 0f, currentFound = false, nextSide = null, confidence = 0f)

    @Test fun idleWhenAutoOff() {
        val g = DecisionGate()
        assertEquals(GateResult.Idle, g.onFrame(0, 0, seen(Side.LEFT), autoEnabled = false))
    }

    @Test fun needsConsecutiveFrames() {
        val g = DecisionGate(GateConfig(requiredFrames = 2), Side.LEFT)
        assertEquals(GateResult.Wait, g.onFrame(0, 0, seen(Side.LEFT), true))
        assertEquals(GateResult.Fire(Action.CLIMB), g.onFrame(16 * ms, 16 * ms, seen(Side.LEFT), true))
    }

    @Test fun flickerResetsStreak() {
        val g = DecisionGate(GateConfig(requiredFrames = 2), Side.LEFT)
        g.onFrame(0, 0, seen(Side.LEFT), true)
        assertEquals(GateResult.Wait, g.onFrame(16 * ms, 16 * ms, seen(null), true))
        assertEquals(GateResult.Wait, g.onFrame(32 * ms, 32 * ms, seen(Side.LEFT), true))
    }

    @Test fun lowConfidenceNeverFires() {
        val g = DecisionGate(GateConfig(requiredFrames = 1, minConfidence = 0.5f), Side.LEFT)
        assertEquals(GateResult.Wait, g.onFrame(0, 0, seen(Side.LEFT, conf = 0.3f), true))
    }

    /** 회전 직후 옛 프레임을 보고 한 칸 더 오르는 사고를 막는다. */
    @Test fun staleFramesAfterTurnAreIgnored() {
        val g = DecisionGate(GateConfig(requiredFrames = 1, settleMs = 90), Side.LEFT)
        assertEquals(GateResult.Fire(Action.TURN_RIGHT), g.onFrame(0, 10 * ms, seen(Side.RIGHT), true))
        assertEquals(Side.RIGHT, g.facing)
        // 입력 시각(10ms) 이후 90ms 안의 프레임: 옛 화면일 수 있으므로 무시
        assertEquals(GateResult.Wait, g.onFrame(50 * ms, 60 * ms, seen(Side.RIGHT), true))
        // 충분히 지난 새 프레임은 정상 처리
        assertEquals(GateResult.Fire(Action.CLIMB), g.onFrame(120 * ms, 120 * ms, seen(Side.RIGHT), true))
    }

    @Test fun haltsWhenCurrentStepLost() {
        val g = DecisionGate(GateConfig(lostTimeoutMs = 800), Side.LEFT)
        g.onFrame(0, 0, seen(Side.LEFT), true)
        assertEquals(GateResult.Wait, g.onFrame(500 * ms, 500 * ms, lost, true))
        assertTrue(g.onFrame(900 * ms, 900 * ms, lost, true) is GateResult.Halt)
    }

    @Test fun unknownNeverFires() {
        val g = DecisionGate(GateConfig(requiredFrames = 1), Side.LEFT)
        repeat(10) { i -> assertEquals(GateResult.Wait, g.onFrame(i * 16 * ms, i * 16 * ms, seen(null), true)) }
    }
}
