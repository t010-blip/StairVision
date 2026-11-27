package kr.stairvision.zombie

/** 주문 하나: 피규어 1개(단품) 또는 서로 다른 피규어 2개(세트). */
data class Order(val id: Int, val figures: List<Figure>) {
    init {
        require(figures.size in 1..2) { "주문은 피규어 1~2개" }
        require(figures.size == 1 || figures[0] != figures[1]) { "같은 피규어 두 개는 폴라로이드가 되어 쓸모없음" }
    }

    val isSet: Boolean get() = figures.size == 2
    val title: String get() = figures.joinToString("+") { it.name } + if (isSet) " (세트)" else ""
}

/** 주문 목록을 할 일 순서와 꽃 합계로 바꾼다. 먼저 들어온 주문이 앞선다. */
object Planner {

    /** 한 주문을 만드는 순서. */
    fun steps(o: Order): List<String> {
        val lines = o.figures.map { f -> "${f.recipe} → 화로 → ${f.name}" }.toMutableList()
        lines += if (o.isSet) "포토부스 → 포장 → 보따리" else "포장 → 보따리"
        return lines
    }

    /**
     * 열린 주문 전체에 필요한 꽃 개수. 가장 먼저 필요한 꽃이 앞에 오도록
     * (첫 등장 주문 순서, 같으면 개수 많은 순) 정렬한다.
     */
    fun flowerTotals(orders: List<Order>): List<Pair<String, Int>> {
        val count = LinkedHashMap<String, Int>()
        val firstSeen = HashMap<String, Int>()
        orders.forEachIndexed { i, o ->
            o.figures.forEach { f ->
                for (fl in listOf(f.flowerA, f.flowerB)) {
                    count[fl] = (count[fl] ?: 0) + 1
                    firstSeen.putIfAbsent(fl, i)
                }
            }
        }
        return count.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { firstSeen[it.key] }.thenByDescending { it.value })
            .map { it.key to it.value }
    }

    /** 오버레이에 띄울 전체 안내문. collapsed면 첫 주문과 합계만. */
    fun render(orders: List<Order>, collapsed: Boolean): String {
        if (orders.isEmpty()) return "주문 없음 · '+주문'으로 화면 상단 주문을 추가하세요"
        val sb = StringBuilder()
        val shown = if (collapsed) orders.take(1) else orders
        shown.forEachIndexed { i, o ->
            sb.append(CIRCLED.getOrElse(i) { "${i + 1}." }).append(' ').append(o.title).append('\n')
            steps(o).forEach { sb.append("   ").append(it).append('\n') }
        }
        if (collapsed && orders.size > 1) sb.append("   … 외 ${orders.size - 1}개\n")
        sb.append("꽃 합계: ")
        sb.append(flowerTotals(orders).joinToString(" · ") { (f, n) -> "$f $n" })
        return sb.toString()
    }

    private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧")
}
