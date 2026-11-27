package kr.stairvision.zombie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class NavGridTest {
    /** 가운데에 세로 벽(아래쪽만 뚫림)이 있는 방. 지도 칸 단위. */
    private val wall = Area(98f, -30f, 102f, 80f)
    private val a = Station(1, StationType.SEED, "흑장미", 60f, 20f)
    private val b = Station(2, StationType.POT, null, 140f, 20f)
    private val corners = listOf(Station(9, StationType.BAG, null, 0f, 0f), Station(10, StationType.BAG, null, 200f, 110f))

    private fun model(): NavModel {
        val st = listOf(a, b) + corners
        val g = NavGrid.build(st, listOf(wall), cell = 4f, margin = 2, inflate = 2f)!!
        return NavModel(g, st, reach = 10f)
    }

    @Test fun pathGoesAroundWall() {
        val m = model()
        val p = m.path(60f, 20f, b)
        // 벽 아래(y>80)로 돌아가야 한다
        assertTrue("경로가 벽 아래로 돌아가야 함: $p", p.any { it.second > 80f })
        val len = p.zipWithNext { u, v -> hypot(v.first - u.first, v.second - u.second) }.sum()
        assertTrue("len=$len", len > 130f) // 직선 80보다 훨씬 김
        assertTrue(m.distance(60f, 20f, b) > 110f)
    }

    @Test fun smoothedPathHasFewCorners() {
        val p = model().path(60f, 20f, b)
        assertTrue("꺾임이 적어야 함: ${p.size}", p.size <= 6)
    }

    @Test fun plannerPrefersWalkingDistance() {
        // 직선으로는 벽 너머 화분(110,20)이 가깝지만, 걸어서는 같은 쪽 화분(40,70)이 가깝다
        val far = Station(3, StationType.POT, null, 112f, 20f)
        val near = Station(4, StationType.POT, null, 40f, 70f)
        val seeds = listOf(
            Station(5, StationType.SEED, "선인장", 80f, 20f),
            Station(6, StationType.SEED, "흑장미", 80f, 30f),
        )
        val st = seeds + far + near + corners
        val g = NavGrid.build(st, listOf(wall), cell = 4f, margin = 2, inflate = 2f)!!
        val m = NavModel(g, st, reach = 10f)
        val r = RoutePlanner.plan(Order(1, listOf(Recipes.byName("남동진")!!)), st, 70f, 25f) { x, y, s -> m.distance(x, y, s) }
        assertEquals(4, r[1].station?.id)
        val straight = RoutePlanner.plan(Order(1, listOf(Recipes.byName("남동진")!!)), st, 70f, 25f)
        assertEquals(3, straight[1].station?.id)
    }
}
