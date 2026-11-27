package kr.stairvision.zombie

import kotlin.math.abs

/** 지도에 등록한 지형지물(씨앗 상자, 화분 등) 하나. 좌표는 지도 칸 단위, 조각은 등록 당시 모습. */
data class Landmark(val id: Int, val mapX: Float, val mapY: Float, val patch: GrayImage) {
    val half: Int get() = patch.w / 2
    val patchMean: Float = patch.mean()
}

data class TrackerParams(
    /** 프레임 간 화면 이동 탐색 범위(칸). */
    val globalSearch: Int = 6,
    /** 지형지물 위치 보정 탐색 범위(칸). */
    val localSearch: Int = 6,
    /** 평균을 뺀 픽셀당 차이가 이 값 미만이면 같은 곳으로 본다. */
    val matchThreshold: Float = 20f,
    /** 이동 추정에 쓰는 화면 영역(비율). 상단 주문·하단 조작 버튼·채팅을 뺀다. */
    val regionLeft: Float = 0.15f,
    val regionRight: Float = 0.85f,
    val regionTop: Float = 0.2f,
    val regionBottom: Float = 0.75f,
    /** 캐릭터 주변(비율)은 캐릭터와 함께 움직이지 않으므로 뺀다. */
    val charHalfW: Float = 0.06f,
    val charHalfH: Float = 0.16f,
    /** 지형지물을 이 프레임 수만큼 못 찾으면 화면 전체에서 다시 찾는다. */
    val relocalizeAfter: Int = 5,
)

/**
 * 따라다니는 카메라의 위치를 추적한다. 화면 좌표 = 지도 좌표 − 카메라.
 *
 * 1. 직전 프레임과 비교해 화면이 몇 칸 밀렸는지 구해 카메라를 옮긴다(누적).
 * 2. 등록한 지형지물이 보이면 그 위치로 카메라를 바로잡는다(누적 오차 제거).
 * 3. 한동안 하나도 못 찾으면(재시작·스테이지 전환) 화면 전체에서 지형지물을 찾는다.
 */
class CameraTracker(@Volatile var params: TrackerParams = TrackerParams()) {
    var camX = 0f
        private set
    var camY = 0f
        private set

    @Volatile var landmarks: List<Landmark> = emptyList()

    /** 캐릭터의 화면 위치(비율). 이동 추정에서 이 주변을 뺀다. */
    @Volatile var charFx = 0.5f
    @Volatile var charFy = 0.52f

    /** 마지막 프레임에서 찾은 지형지물 수. */
    var matched = 0
        private set
    var lostFrames = 0
        private set

    private var prev: GrayImage? = null
    private var relocIndex = 0

    fun reset(x: Float = 0f, y: Float = 0f) {
        camX = x
        camY = y
        prev = null
        lostFrames = 0
    }

    fun update(img: GrayImage) {
        prev?.let { p ->
            val (dx, dy) = globalShift(p, img)
            // 화면 내용이 +d 만큼 밀렸다 = 카메라가 −d 만큼 움직였다
            camX -= dx
            camY -= dy
        }
        prev = img

        val lms = landmarks
        if (lms.isEmpty()) {
            matched = 0
            return
        }

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (lm in lms) {
            val px = (lm.mapX - camX).toInt()
            val py = (lm.mapY - camY).toInt()
            val hit = search(img, lm, px, py, params.localSearch, 1) ?: continue
            xs += lm.mapX - hit.first
            ys += lm.mapY - hit.second
        }
        if (xs.isEmpty() && lostFrames >= params.relocalizeAfter) {
            val lm = lms[relocIndex++ % lms.size]
            relocalize(img, lm)?.let { (x, y) ->
                xs += lm.mapX - x
                ys += lm.mapY - y
            }
        }
        matched = xs.size
        if (xs.isEmpty()) {
            lostFrames++
            return
        }
        lostFrames = 0
        xs.sort(); ys.sort()
        camX = xs[xs.size / 2]
        camY = ys[ys.size / 2]
    }

    /** 직전 → 현재 프레임의 화면 이동량(칸). */
    fun globalShift(a: GrayImage, b: GrayImage): Pair<Int, Int> {
        val p = params
        val s = p.globalSearch
        val x0 = maxOf(s, (a.w * p.regionLeft).toInt())
        val x1 = minOf(a.w - s - 1, (a.w * p.regionRight).toInt())
        val y0 = maxOf(s, (a.h * p.regionTop).toInt())
        val y1 = minOf(a.h - s - 1, (a.h * p.regionBottom).toInt())
        val cx0 = ((charFx - p.charHalfW) * a.w).toInt()
        val cx1 = ((charFx + p.charHalfW) * a.w).toInt()
        val cy0 = ((charFy - p.charHalfH) * a.h).toInt()
        val cy1 = ((charFy + p.charHalfH) * a.h).toInt()

        var best = Float.MAX_VALUE
        var bdx = 0
        var bdy = 0
        for (dy in -s..s) for (dx in -s..s) {
            var sum = 0L
            var n = 0
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    if (x !in cx0..cx1 || y !in cy0..cy1) {
                        sum += abs(a[x, y] - b[x + dx, y + dy])
                        n++
                    }
                    x++
                }
                y++
            }
            if (n == 0) continue
            // 같은 점수면 덜 움직인 쪽을 고른다(반복 무늬 바닥 대비)
            val score = sum.toFloat() / n + 0.05f * (abs(dx) + abs(dy))
            if (score < best) {
                best = score; bdx = dx; bdy = dy
            }
        }
        return bdx to bdy
    }

    /** 예상 위치 (px, py) 주변 ±r에서 지형지물을 찾는다. 찾으면 화면 칸 좌표. */
    fun search(img: GrayImage, lm: Landmark, px: Int, py: Int, r: Int, step: Int): Pair<Float, Float>? {
        val h = lm.half
        var best = Float.MAX_VALUE
        var bx = 0
        var by = 0
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                val cx = px + dx
                val cy = py + dy
                if (cx - h >= 0 && cy - h >= 0 && cx + h <= img.w && cy + h <= img.h) {
                    val d = zeroMeanDiff(img, cx - h, cy - h, lm)
                    if (d < best) {
                        best = d; bx = cx; by = cy
                    }
                }
                dx += step
            }
            dy += step
        }
        return if (best < params.matchThreshold) bx.toFloat() to by.toFloat() else null
    }

    private fun relocalize(img: GrayImage, lm: Landmark): Pair<Float, Float>? {
        val coarse = search(img, lm, img.w / 2, img.h / 2, maxOf(img.w, img.h) / 2, 2) ?: return null
        return search(img, lm, coarse.first.toInt(), coarse.second.toInt(), 2, 1)
    }

    /** 밝기 차이를 무시한(평균을 뺀) 픽셀당 평균 절대 차이. */
    private fun zeroMeanDiff(img: GrayImage, x0: Int, y0: Int, lm: Landmark): Float {
        val pw = lm.patch.w
        var sum = 0L
        for (y in 0 until pw) for (x in 0 until pw) sum += img[x0 + x, y0 + y]
        val m = sum.toFloat() / (pw * pw)
        var d = 0f
        for (y in 0 until pw) for (x in 0 until pw) {
            d += abs((img[x0 + x, y0 + y] - m) - (lm.patch[x, y] - lm.patchMean))
        }
        return d / (pw * pw)
    }
}
