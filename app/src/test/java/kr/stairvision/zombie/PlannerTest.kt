package kr.stairvision.zombie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private fun fig(n: String) = Recipes.byName(n)!!

    @Test fun recipeTableIsComplete() {
        assertEquals(24, Recipes.figures.size)
        assertEquals(24, Recipes.figures.map { it.name }.toSet().size)
        val used = Recipes.figures.flatMap { listOf(it.flowerA, it.flowerB) }.toSet()
        assertEquals(Recipes.flowers.toSet(), used)
    }

    @Test fun everyFlowerPairMakesAtMostOneFigure() {
        val pairs = Recipes.figures.map { setOf(it.flowerA, it.flowerB) to it.flowerA }
        assertEquals(24, Recipes.figures.map { listOf(it.flowerA, it.flowerB).sorted() }.toSet().size)
        assertTrue(pairs.isNotEmpty())
    }

    @Test fun reverseLookupIgnoresOrder() {
        assertEquals("사무엘", Recipes.fromFlowers("솔방울", "방울꽃")?.name)
        assertEquals("김준호", Recipes.fromFlowers("홍매화", "홍매화")?.name)
        assertNull(Recipes.fromFlowers("백합", "선인장")) // 꽃다발
    }

    @Test fun setOrderSteps() {
        val o = Order(1, listOf(fig("사무엘"), fig("백설현")))
        assertEquals(
            listOf("방울꽃+솔방울 → 화로 → 사무엘", "백합+목화솜 → 화로 → 백설현", "포토부스 → 포장 → 보따리"),
            Planner.steps(o),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameFigureTwiceIsRejected() {
        Order(1, listOf(fig("케빈"), fig("케빈")))
    }

    @Test fun flowerTotalsFollowOrderPriority() {
        val orders = listOf(
            Order(1, listOf(fig("강우빈"))),           // 선인장, 솔방울
            Order(2, listOf(fig("사무엘"), fig("홍산하"))), // 방울꽃, 솔방울, 솔방울, 클로버
        )
        val t = Planner.flowerTotals(orders)
        assertEquals("솔방울" to 3, t[0]) // 첫 주문에 있고 개수가 많음
        assertEquals("선인장" to 1, t[1])
        assertEquals(setOf("방울꽃", "클로버"), t.drop(2).map { it.first }.toSet())
    }

    @Test fun renderCollapsed() {
        val orders = listOf(Order(1, listOf(fig("케빈"))), Order(2, listOf(fig("우연"))))
        val s = Planner.render(orders, collapsed = true)
        assertTrue(s.contains("① 케빈"))
        assertTrue(s.contains("외 1개"))
        assertTrue(s.contains("꽃 합계: 홍매화 1 · 흑장미 1 · 선인장 1 · 클로버 1"))
    }
}
