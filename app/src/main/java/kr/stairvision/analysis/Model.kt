package kr.stairvision.analysis

import kotlin.math.abs

/** 캐릭터가 바라보는 방향, 또는 다음 발판이 놓인 쪽. */
enum class Side {
    LEFT, RIGHT;

    fun flip(): Side = if (this == LEFT) RIGHT else LEFT
    fun label(): String = if (this == LEFT) "왼쪽" else "오른쪽"
}

enum class Action { CLIMB, TURN_LEFT, TURN_RIGHT, UNKNOWN }

/** 0..1로 정규화한 화면 좌표. 해상도가 달라도 같은 위치를 가리킨다. */
data class NPoint(val x: Float, val y: Float)

/**
 * 보정 결과.
 * - currentStep: 캐릭터가 서 있는 발판(가려지지 않은 부분)
 * - nextStep: 그 바로 위 발판(왼쪽이든 오른쪽이든). 한 칸의 가로·세로 간격을 여기서 얻는다.
 * - stepColor: 현재 발판에서 뽑은 기준 색(0xRRGGBB)
 */
data class Calibration(
    val currentStep: NPoint,
    val nextStep: NPoint,
    val climbButton: NPoint,
    val turnButton: NPoint,
    val stepColor: Int,
) {
    /** 한 칸의 가로 간격(화면 너비 대비). */
    val dx: Float get() = abs(nextStep.x - currentStep.x)

    /** 한 칸의 세로 간격(화면 높이 대비). 위쪽이 양수. */
    val dy: Float get() = currentStep.y - nextStep.y

    fun isValid(): Boolean = dx >= 0.01f && dy >= 0.005f
}

data class Analysis(
    val currentScore: Float,
    val leftScore: Float,
    val rightScore: Float,
    /** 현재 발판 영역이 자기 색 구성과 일치하는가(표시용). */
    val currentFound: Boolean,
    /** 다음 발판이 한쪽에만 확실히 있을 때만 값이 있다. */
    val nextSide: Side?,
    /** 0..1. 위 줄 두 후보의 점수 차이. 판단이 없으면 0. */
    val confidence: Float,
    /** 이전(아래 줄) 발판이 놓인 쪽. 게임 시작 직후처럼 없으면 null. */
    val prevSide: Side? = null,
    val belowLeftScore: Float = 0f,
    val belowRightScore: Float = 0f,
    val paletteSize: Int = 1,
) {
    /** 캐릭터는 이전 발판에서 바라보는 방향으로 올라왔으므로 이전 발판의 반대쪽을 본다. */
    val inferredFacing: Side? get() = prevSide?.flip()
}

/** 계획서의 행동 판단표. 반대쪽 발판이 실제로 확인돼야 회전한다. */
fun decide(nextSide: Side?, facing: Side): Action = when (nextSide) {
    null -> Action.UNKNOWN
    facing -> Action.CLIMB
    Side.LEFT -> Action.TURN_LEFT
    Side.RIGHT -> Action.TURN_RIGHT
}

fun Action.label(): String = when (this) {
    Action.CLIMB -> "CLIMB 전진"
    Action.TURN_LEFT -> "TURN_LEFT 좌회전"
    Action.TURN_RIGHT -> "TURN_RIGHT 우회전"
    Action.UNKNOWN -> "UNKNOWN 보류"
}

/** 방향 전환 버튼은 좌·우 구분 없이 하나다. */
fun Action.usesTurnButton(): Boolean = this == Action.TURN_LEFT || this == Action.TURN_RIGHT
