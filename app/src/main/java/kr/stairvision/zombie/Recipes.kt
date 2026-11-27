package kr.stairvision.zombie

/** 좀비고등학교 크리스마스 익스프레스 2 피규어 레시피 (출처: 나무위키 좀비고등학교/이벤트/크리스마스). */
data class Figure(val name: String, val flowerA: String, val flowerB: String) {
    /** 오버레이 버튼용 짧은 이름. */
    val short: String get() = name.substringBefore(' ')
    val recipe: String get() = "$flowerA+$flowerB"
}

object Recipes {
    val figures: List<Figure> = listOf(
        Figure("김준호", "홍매화", "홍매화"),
        Figure("케빈", "홍매화", "흑장미"),
        Figure("신가희", "솔방울", "목화솜"),
        Figure("정예슬", "솔방울", "홍매화"),
        Figure("하린", "무궁화", "백합"),
        Figure("곽준형", "방울꽃", "민들레"),
        Figure("조연빈", "민들레", "목화솜"),
        Figure("조예지", "민들레", "클로버"),
        Figure("남선지", "클로버", "목화솜"),
        Figure("이유리", "민들레", "흑장미"),
        Figure("편준범", "클로버", "흑장미"),
        Figure("우연", "선인장", "클로버"),
        Figure("아람", "방울꽃", "수레국화"),
        Figure("고나래", "방울꽃", "클로버"),
        Figure("남동진", "선인장", "흑장미"),
        Figure("마용규", "제비꽃", "목화솜"),
        Figure("임현지", "솔방울", "제비꽃"),
        Figure("박찬영", "방울꽃", "홍매화"),
        Figure("정동석", "흑장미", "목화솜"),
        Figure("홍산하", "솔방울", "클로버"),
        Figure("나르샤 그린필드", "솔방울", "민들레"),
        Figure("강우빈", "선인장", "솔방울"),
        Figure("사무엘", "방울꽃", "솔방울"),
        Figure("백설현", "백합", "목화솜"),
    )

    val flowers: List<String> = listOf(
        "홍매화", "흑장미", "무궁화", "백합", "목화솜", "솔방울",
        "방울꽃", "민들레", "클로버", "선인장", "수레국화", "제비꽃",
    )

    private val byName = figures.associateBy { it.name }

    fun byName(name: String): Figure? = byName[name]

    /** 화로에 넣은 두 꽃으로 나오는 피규어. 없으면 null(꽃다발, 쓸모없음). 순서는 상관없다. */
    fun fromFlowers(x: String, y: String): Figure? = figures.firstOrNull {
        (it.flowerA == x && it.flowerB == y) || (it.flowerA == y && it.flowerB == x)
    }
}
