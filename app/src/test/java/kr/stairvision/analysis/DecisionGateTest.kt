package kr.stairvision.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionGateTest {
    private val ms = 1_000_000L

    private fun seen(side: Side?, conf: Float = 0.9f, prev: Side? = null) = Analysis(
        0.95f, 0f, 0f, currentFound = true, nextSide = side,
        confidence = if (side == null) 0f else conf, prevSide = prev,
    )

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
        assertEquals(GateResult.Wait, g.onFrame(50 * ms, 60 * ms, seen(Side.RIGHT), true))
        assertEquals(GateResult.Fire(Action.CLIMB), g.onFrame(120 * ms, 120 * ms, seen(Side.RIGHT), true))
    }

    /** 화면에서 읽은 방향이 잘못 추적된 방향을 바로잡는다. */
    @Test fun visibleFacingOverridesTrackedFacing() {
        val g = DecisionGate(GateConfig(requiredFrames = 1), Side.LEFT) // 추적값은 왼쪽(틀림)
        // 이전 발판이 왼쪽 아래 → 실제로는 오른쪽을 봄. 다음 발판도 오른쪽 위 → 오르기
        assertEquals(GateResult.Fire(Action.CLIMB), g.onFrame(0, 0, seen(Side.RIGHT, prev = Side.LEFT), true))
        assertEquals(Side.RIGHT, g.facing)
    }

    @Test fun haltsWhenStalled() {
        val g = DecisionGate(GateConfig(stallTimeoutMs = 1000), Side.LEFT)
        assertEquals(GateResult.Wait, g.onFrame(0, 0, seen(null), true))
        assertEquals(GateResult.Wait, g.onFrame(600 * ms, 600 * ms, seen(null), true))
        assertTrue(g.onFrame(1100 * ms, 1100 * ms, seen(null), true) is GateResult.Halt)
    }

    @Test fun confidentFrameResetsStallTimer() {
        val g = DecisionGate(GateConfig(stallTimeoutMs = 1000, requiredFrames = 5), Side.LEFT)
        g.onFrame(0, 0, seen(null), true)
        g.onFrame(900 * ms, 900 * ms, seen(Side.LEFT), true)
        assertEquals(GateResult.Wait, g.onFrame(1500 * ms, 1500 * ms, seen(null), true))
    }
}
