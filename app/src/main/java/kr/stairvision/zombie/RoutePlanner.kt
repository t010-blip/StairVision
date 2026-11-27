package kr.stairvision.zombie

import kotlin.math.hypot

enum class StationType(val label: String) {
    SEED("씨앗 상자"),
    POT("화분"),
    BRAZIER("화로"),
    BOOTH("포토부스"),
    WRAP("포장대"),
    BAG("선물 보따리"),
}

/** 지도에 등록한 작업대. 좌표는 지도 칸 단위. 씨앗 상자는 꽃 이름을 가진다. */
data class Station(
    val id: Int,
    val type: StationType,
    val flower: String?,
    val x: Float,
    val y: Float,
) {
    val name: String get() = if (type == StationType.SEED) "$flower ${type.label}" else type.label
}

/** 경로의 한 지점. station이 null이면 해당 작업대가 지도에 없다. */
data class RouteStep(val station: Station?, val action: String, val missing: StationType? = null)

/**
 * 주문 하나를 끝내는 이동 순서. 한 번에 물건 하나만 든다고 보고,
 * 같은 종류 작업대가 여럿이면 지금 위치에서 가장 가까운 곳을 고른다.
 */
object RoutePlanner {

    /**
     * @param dist 현재 위치에서 작업대까지 실제 걷는 거리. 없으면 직선거리.
     *             길이 없는 작업대(INF)는 다른 후보가 있으면 고르지 않는다.
     */
    fun plan(
        order: Order,
        stations: List<Station>,
        startX: Float,
        startY: Float,
        dist: ((Float, Float, Station) -> Float)? = null,
    ): List<RouteStep> {
        var cx = startX
        var cy = startY
        val raw = ArrayList<RouteStep>()

        fun nearest(type: StationType, flower: String? = null, exclude: Station? = null): Station? =
            stations.filter { it.type == type && (flower == null || it.flower == flower) && it != exclude }
                .minByOrNull {
                    val straight = hypot(it.x - cx, it.y - cy)
                    val d = dist?.invoke(cx, cy, it) ?: straight
                    if (d == NavGrid.INF) 1e9f + straight else d
                }

        fun go(st: Station?, action: String, type: StationType) {
            raw += RouteStep(st, action, if (st == null) type else null)
            if (st != null) {
                cx = st.x; cy = st.y
            }
        }

        var booth: Station? = null
        for (f in order.figures) {
            val seedA = nearest(StationType.SEED, f.flowerA)
            go(seedA, "${f.flowerA} 씨앗 집기", StationType.SEED)
            val pot1 = nearest(StationType.POT)
            go(pot1, "화분에 심기", StationType.POT)
            val seedB = nearest(StationType.SEED, f.flowerB)
            go(seedB, "${f.flowerB} 씨앗 집기", StationType.SEED)
            val pot2 = nearest(StationType.POT, exclude = pot1) ?: pot1
            go(pot2, "화분에 심기", StationType.POT)
            go(pot1, "${f.flowerA} 따기", StationType.POT)
            val brazier = nearest(StationType.BRAZIER)
            go(brazier, "화로에 넣기", StationType.BRAZIER)
            go(pot2, "${f.flowerB} 따기", StationType.POT)
            go(brazier, "화로에 넣기", StationType.BRAZIER)
            go(brazier, "${f.name} 꺼내기", StationType.BRAZIER)
            if (order.isSet) {
                if (booth == null) booth = nearest(StationType.BOOTH)
                go(booth, "포토부스에 넣기", StationType.BOOTH)
            }
        }
        if (order.isSet) go(booth, "세트 꺼내기", StationType.BOOTH)
        go(nearest(StationType.WRAP), "포장 (화면 연타)", StationType.WRAP)
        go(nearest(StationType.BAG), "보따리에 넣기", StationType.BAG)
        return merge(raw)
    }

    /** 같은 작업대에서 연달아 하는 일은 한 지점으로 합친다. */
    private fun merge(steps: List<RouteStep>): List<RouteStep> {
        val out = ArrayList<RouteStep>()
        for (s in steps) {
            val last = out.lastOrNull()
            if (last != null && last.station != null && last.station == s.station) {
                out[out.lastIndex] = last.copy(action = "${last.action} → ${s.action}")
            } else {
                out += s
            }
        }
        return out
    }
}
