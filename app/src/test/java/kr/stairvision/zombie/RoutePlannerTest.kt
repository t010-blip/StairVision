package kr.stairvision.zombie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private fun fig(n: String) = Recipes.byName(n)!!

    /** 스크린샷의 1스테이지 배치를 본뜬 지도(칸 단위). */
    private val stations = listOf(
        Station(1, StationType.SEED, "흑장미", 30f, 20f),
        Station(2, StationType.SEED, "선인장", 30f, 30f),
        Station(3, StationType.SEED, "민들레", 30f, 40f),
        Station(4, StationType.POT, null, 120f, 35f),
        Station(5, StationType.POT, null, 120f, 45f),
        Station(6, StationType.POT, null, 120f, 55f),
        Station(7, StationType.BRAZIER, null, 30f, 60f),
        Station(8, StationType.WRAP, null, 80f, 10f),
        Station(9, StationType.BAG, null, 150f, 40f),
    )

    @Test fun singleOrderRoute() {
        val r = RoutePlanner.plan(Order(1, listOf(fig("남동진"))), stations, 75f, 40f) // 선인장+흑장미
        val names = r.map { it.station?.id }
        assertEquals(listOf(2, 4, 1, 5, 4, 7, 5, 7, 8, 9), names)
        assertEquals("선인장 씨앗 집기", r[0].action)
        assertEquals("화로에 넣기 → 남동진 꺼내기", r[7].action) // 같은 작업대는 한 지점으로
        assertTrue(r.all { it.missing == null })
    }

    @Test fun sameFlowerTwiceUsesTwoPots() {
        val r = RoutePlanner.plan(Order(1, listOf(fig("김준호"))), stations + Station(10, StationType.SEED, "홍매화", 30f, 50f), 75f, 40f)
        val pots = r.filter { it.station?.type == StationType.POT }.map { it.station!!.id }.toSet()
        assertEquals(2, pots.size)
    }

    @Test fun setOrderGoesThroughBooth() {
        val withBooth = stations + Station(10, StationType.BOOTH, null, 100f, 70f)
        val r = RoutePlanner.plan(Order(1, listOf(fig("남동진"), fig("이유리"))), withBooth, 75f, 40f)
        val booths = r.filter { it.station?.type == StationType.BOOTH }
        assertEquals(2, booths.size)
        assertTrue(booths.last().action.endsWith("세트 꺼내기"))
        assertEquals(StationType.BAG, r.last().station?.type)
    }

    @Test fun missingStationIsReported() {
        val r = RoutePlanner.plan(Order(1, listOf(fig("하린"))), stations, 75f, 40f) // 무궁화·백합 상자 없음
        assertEquals(StationType.SEED, r.first().missing)
    }
}
