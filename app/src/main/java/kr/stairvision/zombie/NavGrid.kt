package kr.stairvision.zombie

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 지도 칸 단위 사각형(장애물). */
data class Area(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun normalized() = Area(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
}

/**
 * 길찾기 격자. 한 칸 = 지도 칸 cell개. 8방향 이동, 장애물 모서리는 가로지르지 않는다.
 * 범위는 등록한 작업대·장애물을 감싸는 사각형 + margin.
 */
class NavGrid(
    val originX: Float,
    val originY: Float,
    val cell: Float,
    val w: Int,
    val h: Int,
    val blocked: BooleanArray,
) {
    fun index(cx: Int, cy: Int) = cy * w + cx
    fun cx(i: Int) = i % w
    fun cy(i: Int) = i / w
    fun inside(cx: Int, cy: Int) = cx in 0 until w && cy in 0 until h
    fun free(cx: Int, cy: Int) = inside(cx, cy) && !blocked[index(cx, cy)]

    /** 지도 좌표 → 격자 칸(범위 밖이면 가장 가까운 가장자리 칸). */
    fun cellOf(x: Float, y: Float): Int {
        val cx = floor((x - originX) / cell).toInt().coerceIn(0, w - 1)
        val cy = floor((y - originY) / cell).toInt().coerceIn(0, h - 1)
        return index(cx, cy)
    }

    fun centerX(i: Int) = originX + (cx(i) + 0.5f) * cell
    fun centerY(i: Int) = originY + (cy(i) + 0.5f) * cell

    /** i가 막혀 있으면 가장 가까운 빈칸. */
    fun nearestFree(i: Int, maxR: Int = 8): Int? {
        if (!blocked[i]) return i
        val x = cx(i)
        val y = cy(i)
        var best: Int? = null
        var bestD = Int.MAX_VALUE
        for (r in 1..maxR) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                if (free(x + dx, y + dy)) {
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d; best = index(x + dx, y + dy)
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    /** 여러 출발점에서의 최단 거리(지도 칸 단위). 닿지 않는 칸은 INF. */
    fun field(sources: Collection<Int>): FloatArray {
        val dist = FloatArray(w * h) { INF }
        val pq = PriorityQueue<Long>(compareBy { java.lang.Float.intBitsToFloat((it ushr 32).toInt()) })
        for (s in sources) {
            if (blocked[s]) continue
            dist[s] = 0f
            pq.add(pack(0f, s))
        }
        while (pq.isNotEmpty()) {
            val top = pq.poll()!!
            val d = java.lang.Float.intBitsToFloat((top ushr 32).toInt())
            val i = (top and 0xFFFFFFFFL).toInt()
            if (d > dist[i]) continue
            val x = cx(i)
            val y = cy(i)
            for (k in 0 until 8) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (!free(nx, ny)) continue
                if (DX[k] != 0 && DY[k] != 0 && (!free(x + DX[k], y) || !free(x, y + DY[k]))) continue
                val nd = d + STEP[k] * cell
                val ni = index(nx, ny)
                if (nd < dist[ni]) {
                    dist[ni] = nd
                    pq.add(pack(nd, ni))
                }
            }
        }
        return dist
    }

    /** 거리장을 따라 내려가 출발점(거리 0)까지의 칸 목록. 닿을 수 없으면 null. */
    fun descend(field: FloatArray, start: Int): List<Int>? {
        if (field[start] == INF) return null
        val path = arrayListOf(start)
        var i = start
        var guard = w * h
        while (field[i] > 0f && guard-- > 0) {
            val x = cx(i)
            val y = cy(i)
            var best = i
            for (k in 0 until 8) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (!free(nx, ny)) continue
                if (DX[k] != 0 && DY[k] != 0 && (!free(x + DX[k], y) || !free(x, y + DY[k]))) continue
                val ni = index(nx, ny)
                if (field[ni] < field[best]) best = ni
            }
            if (best == i) break
            i = best
            path += i
        }
        return path
    }

    /** 두 칸 사이 직선이 빈칸만 지나는가(선분이 스치는 모든 칸 검사). */
    fun lineOfSight(a: Int, b: Int): Boolean {
        val x0 = cx(a) + 0.5f
        val y0 = cy(a) + 0.5f
        val x1 = cx(b) + 0.5f
        val y1 = cy(b) + 0.5f
        val n = (max(abs(x1 - x0), abs(y1 - y0)) * 3).toInt() + 1
        for (s in 0..n) {
            val t = s.toFloat() / n
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            // 선이 칸 경계를 지날 때 양옆 칸도 확인해 모서리 통과를 막는다
            for (ox in floatArrayOf(-0.25f, 0.25f)) for (oy in floatArrayOf(-0.25f, 0.25f)) {
                if (!free(floor(x + ox).toInt(), floor(y + oy).toInt())) return false
            }
        }
        return true
    }

    /** 보이는 지점끼리 바로 잇는 줄당기기로 꺾임을 줄인다. */
    fun smooth(path: List<Int>): List<Int> {
        if (path.size <= 2) return path
        val out = arrayListOf(path[0])
        var anchor = 0
        while (anchor < path.lastIndex) {
            var far = anchor + 1
            for (j in path.lastIndex downTo anchor + 2) {
                if (lineOfSight(path[anchor], path[j])) {
                    far = j; break
                }
            }
            out += path[far]
            anchor = far
        }
        return out
    }

    companion object {
        const val INF = Float.MAX_VALUE
        private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        private val STEP = floatArrayOf(1f, 1f, 1f, 1f, sqrt(2f), sqrt(2f), sqrt(2f), sqrt(2f))

        private fun pack(d: Float, i: Int): Long =
            (java.lang.Float.floatToIntBits(d).toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)

        /**
         * @param cell 격자 한 칸 크기(지도 칸)
         * @param margin 등록한 범위 바깥으로 둘 여유(격자 칸)
         * @param inflate 캐릭터 몸집만큼 장애물을 부풀리는 양(지도 칸)
         */
        fun build(
            stations: List<Station>,
            obstacles: List<Area>,
            cell: Float = 4f,
            margin: Int = 1,
            inflate: Float = 2f,
        ): NavGrid? {
            if (stations.isEmpty() && obstacles.isEmpty()) return null
            val ob = obstacles.map { it.normalized() }
            // 여유는 작업대 둘레에만 둔다. 장애물(벽)이 범위 끝까지 그려졌으면 그 바깥으로 돌아가지 못한다.
            val xs = stations.flatMap { listOf(it.x - margin * cell, it.x + margin * cell) } + ob.flatMap { listOf(it.x0, it.x1) }
            val ys = stations.flatMap { listOf(it.y - margin * cell, it.y + margin * cell) } + ob.flatMap { listOf(it.y0, it.y1) }
            val ox = xs.min()
            val oy = ys.min()
            val w = ((xs.max() - ox) / cell).toInt() + 1
            val h = ((ys.max() - oy) / cell).toInt() + 1
            val blocked = BooleanArray(w * h)
            for (a in ob) {
                val x0 = floor((a.x0 - inflate - ox) / cell).toInt()
                val x1 = floor((a.x1 + inflate - ox) / cell).toInt()
                val y0 = floor((a.y0 - inflate - oy) / cell).toInt()
                val y1 = floor((a.y1 + inflate - oy) / cell).toInt()
                for (y in max(0, y0)..min(h - 1, y1)) for (x in max(0, x0)..min(w - 1, x1)) blocked[y * w + x] = true
            }
            return NavGrid(ox, oy, cell, w, h, blocked)
        }
    }
}

/**
 * 작업대마다 '그 작업대 앞(손이 닿는 범위의 빈칸)까지의 거리장'을 미리 구해 둔다.
 * 어느 위치에서든 실제 걸어가는 거리와 최단 경로를 바로 얻을 수 있다.
 */
class NavModel(val grid: NavGrid, stations: List<Station>, reach: Float = 14f) {
    private val fields: Map<Int, FloatArray> = stations.associate { st ->
        val goals = ArrayList<Int>()
        val c = grid.cellOf(st.x, st.y)
        val r = (reach / grid.cell).toInt() + 1
        for (dy in -r..r) for (dx in -r..r) {
            val x = grid.cx(c) + dx
            val y = grid.cy(c) + dy
            if (!grid.free(x, y)) continue
            val i = grid.index(x, y)
            if (hypot(grid.centerX(i) - st.x, grid.centerY(i) - st.y) <= reach) goals += i
        }
        if (goals.isEmpty()) grid.nearestFree(c, 12)?.let { goals += it }
        st.id to grid.field(goals)
    }

    private fun startCell(x: Float, y: Float): Int? = grid.nearestFree(grid.cellOf(x, y), 12)

    /** (x, y)에서 작업대 앞까지 걸어가는 거리(지도 칸). 길이 없으면 INF. */
    fun distance(x: Float, y: Float, st: Station): Float {
        val f = fields[st.id] ?: return NavGrid.INF
        val s = startCell(x, y) ?: return NavGrid.INF
        return f[s]
    }

    /** (x, y)에서 작업대 앞까지의 꺾인 경로(지도 좌표). 길이 없으면 직선. */
    fun path(x: Float, y: Float, st: Station): List<Pair<Float, Float>> {
        val f = fields[st.id]
        val s = startCell(x, y)
        val cells = if (f != null && s != null) grid.descend(f, s) else null
        if (cells == null) return listOf(x to y, st.x to st.y)
        val pts = arrayListOf(x to y)
        grid.smooth(cells).drop(1).forEach { pts += grid.centerX(it) to grid.centerY(it) }
        pts += st.x to st.y
        return pts
    }
}
