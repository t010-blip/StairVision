package kr.stairvision.analysis

data class GateConfig(
    /** 같은 판단이 몇 프레임 연속돼야 입력하는가. */
    val requiredFrames: Int = 2,
    /** 입력 후 이 시간 이전에 찍힌 프레임은 무시한다(애니메이션·지연 프레임). */
    val settleMs: Long = 90,
    /** 이 신뢰도 미만이면 입력하지 않는다. */
    val minConfidence: Float = 0.4f,
    /** 현재 발판이 이 시간 넘게 안 보이면 자동 입력을 끈다. */
    val lostTimeoutMs: Long = 800,
)

sealed class GateResult {
    /** 자동 입력이 꺼져 있음. */
    data object Idle : GateResult()
    /** 아직 입력할 조건이 아님. */
    data object Wait : GateResult()
    data class Fire(val action: Action) : GateResult()
    data class Halt(val reason: String) : GateResult()
}

/**
 * 분석 결과를 실제 입력으로 넘길지 정하는 안전 장치.
 *
 * 핵심 위험: 입력 직후에도 입력 전에 찍힌 프레임이 도착할 수 있다.
 * 예) 회전 입력 뒤 옛 프레임을 보면 '이미 그쪽을 보고 있다'고 판단해 한 칸 더 오른다.
 * 그래서 입력 시각 + settleMs 이전 프레임은 버리고, 같은 판단이 여러 프레임 이어질 때만 입력한다.
 */
class DecisionGate(
    @Volatile var config: GateConfig = GateConfig(),
    initialFacing: Side = Side.LEFT,
) {
    @Volatile var facing: Side = initialFacing

    private var lastInputNs: Long? = null
    private var streakAction = Action.UNKNOWN
    private var streak = 0
    private var lastSeenNs: Long? = null

    fun reset(newFacing: Side) {
        facing = newFacing
        lastInputNs = null
        streakAction = Action.UNKNOWN
        streak = 0
        lastSeenNs = null
    }

    /** 오버레이 표시용 판단(입력 여부와 무관). */
    fun displayAction(a: Analysis): Action = decide(a.nextSide, facing)

    fun onFrame(frameNs: Long, nowNs: Long, a: Analysis, autoEnabled: Boolean): GateResult {
        if (!autoEnabled) {
            streak = 0
            streakAction = Action.UNKNOWN
            lastSeenNs = null
            return GateResult.Idle
        }

        val last = lastInputNs
        if (last != null && frameNs < last + config.settleMs * MS) {
            streak = 0
            streakAction = Action.UNKNOWN
            return GateResult.Wait
        }

        if (a.currentFound) {
            lastSeenNs = frameNs
        } else {
            val seen = lastSeenNs ?: frameNs.also { lastSeenNs = it }
            if (frameNs - seen > config.lostTimeoutMs * MS) {
                return GateResult.Halt("현재 발판을 찾지 못함 (게임 오버 또는 화면 전환)")
            }
        }

        val action = decide(a.nextSide, facing)
        if (action == Action.UNKNOWN || a.confidence < config.minConfidence) {
            streak = 0
            streakAction = Action.UNKNOWN
            return GateResult.Wait
        }

        if (action == streakAction) streak++ else {
            streakAction = action
            streak = 1
        }
        if (streak < config.requiredFrames) return GateResult.Wait

        // 방향 전환 버튼은 전환과 동시에 한 칸 오른다. 따라서 회전도 입력 1회.
        facing = when (action) {
            Action.TURN_LEFT -> Side.LEFT
            Action.TURN_RIGHT -> Side.RIGHT
            else -> facing
        }
        lastInputNs = nowNs
        streak = 0
        streakAction = Action.UNKNOWN
        return GateResult.Fire(action)
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
