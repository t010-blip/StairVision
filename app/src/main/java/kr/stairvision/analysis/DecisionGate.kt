package kr.stairvision.analysis

data class GateConfig(
    /** 같은 판단이 몇 프레임 연속돼야 입력하는가. */
    val requiredFrames: Int = 2,
    /** 입력 후 이 시간 이전에 찍힌 프레임은 무시한다(애니메이션·지연 프레임). */
    val settleMs: Long = 90,
    /** 이 신뢰도 미만이면 입력하지 않는다. */
    val minConfidence: Float = 0.4f,
    /** 확실한 판단이 이 시간 넘게 없으면 자동 입력을 끈다(게임 오버·화면 전환). */
    val stallTimeoutMs: Long = 1200,
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
 * - 현재 방향은 화면의 이전 발판 위치로 정하고, 보이지 않을 때만(게임 시작 직후) 입력 이력으로 추적한 값을 쓴다.
 * - 입력 직후 도착하는 옛 프레임(입력 시각 + settleMs 이전)은 버린다.
 * - 같은 판단이 여러 프레임 이어질 때만 입력한다.
 */
class DecisionGate(
    @Volatile var config: GateConfig = GateConfig(),
    initialFacing: Side = Side.LEFT,
) {
    /** 마지막으로 확정된 캐릭터 방향. */
    @Volatile var facing: Side = initialFacing

    private var lastInputNs: Long? = null
    private var streakAction = Action.UNKNOWN
    private var streak = 0
    private var lastConfidentNs: Long? = null

    fun reset(newFacing: Side) {
        facing = newFacing
        lastInputNs = null
        streakAction = Action.UNKNOWN
        streak = 0
        lastConfidentNs = null
    }

    /** 이 프레임에서 쓸 방향: 화면에서 읽은 값 우선, 없으면 추적값. */
    fun facingFor(a: Analysis): Side = a.inferredFacing ?: facing

    /** 오버레이 표시용 판단(입력 여부와 무관). */
    fun displayAction(a: Analysis): Action = decide(a.nextSide, facingFor(a))

    fun onFrame(frameNs: Long, nowNs: Long, a: Analysis, autoEnabled: Boolean): GateResult {
        if (!autoEnabled) {
            a.inferredFacing?.let { facing = it }
            streak = 0
            streakAction = Action.UNKNOWN
            lastConfidentNs = null
            return GateResult.Idle
        }

        val last = lastInputNs
        if (last != null && frameNs < last + config.settleMs * MS) {
            streak = 0
            streakAction = Action.UNKNOWN
            return GateResult.Wait
        }

        val f = facingFor(a)
        facing = f
        val action = decide(a.nextSide, f)
        if (action == Action.UNKNOWN || a.confidence < config.minConfidence) {
            streak = 0
            streakAction = Action.UNKNOWN
            val since = lastConfidentNs ?: frameNs.also { lastConfidentNs = it }
            if (frameNs - since > config.stallTimeoutMs * MS) {
                return GateResult.Halt("판단 불가 상태가 계속됨 (게임 오버·화면 전환·인식 실패)")
            }
            return GateResult.Wait
        }
        lastConfidentNs = frameNs

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
