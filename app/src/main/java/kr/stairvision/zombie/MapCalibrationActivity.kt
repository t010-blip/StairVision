package kr.stairvision.zombie

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kr.stairvision.analysis.IntArraySource

/**
 * 저장한 게임 화면 위에서 작업대를 눌러 등록하고, 장애물(카운터·테이블·벽)을 끌어서 칠한다.
 * 여러 번 나눠 등록해도 된다: 캡처마다 그때의 카메라 위치가 함께 저장되므로 같은 지도에 이어 붙는다.
 */
class MapCalibrationActivity : Activity() {

    private lateinit var store: MapStore
    private lateinit var view: MapView
    private lateinit var info: TextView
    private var meta: MapStore.CaptureMeta? = null
    private var gray: GrayImage? = null

    private var tool: Tool = Tool.Seed
    private var seedFlower: String = Recipes.flowers.first()
    private val marks = mutableListOf<Mark>()
    private val rects = mutableListOf<RectF>()
    private val history = mutableListOf<Any>()
    private var charMark: Pair<Float, Float>? = null

    private sealed class Tool(val label: String) {
        data object Character : Tool("내 캐릭터")
        data object Seed : Tool("씨앗 상자")
        data object Obstacle : Tool("장애물(끌기)")
        data class Other(val type: StationType) : Tool(type.label)
    }

    /** 화면에 찍은 작업대. fx, fy는 캡처 이미지 비율 좌표. */
    data class Mark(val type: StationType, val flower: String?, val fx: Float, val fy: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MapStore(this)
        meta = store.loadCaptureMeta()
        val bmp = meta?.let { BitmapFactory.decodeFile(store.captureImage.path) }
        if (bmp == null) {
            setContentView(TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 16f
                text = "학습용 화면이 없습니다.\n\n'크익2 가이드 열기' 후 게임 화면에서 가이드 패널의 '지도학습'을 누르고 다시 오세요."
            })
            return
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val g = GrayImage.from(IntArraySource(px, bmp.width, bmp.height), meta!!.cell)
        gray = g

        info = TextView(this).apply { textSize = 13f; setPadding(16, 6, 16, 6) }
        view = MapView(this, bmp).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onTap = { fx, fy -> onTap(fx, fy) }
            onRect = { r -> if (tool is Tool.Obstacle) addRect(r) }
            dragMode = { tool is Tool.Obstacle }
        }
        showExisting(g)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val all = listOf(Tool.Character, Tool.Seed) +
            listOf(StationType.POT, StationType.BRAZIER, StationType.BOOTH, StationType.WRAP, StationType.BAG).map { Tool.Other(it) } +
            Tool.Obstacle
        all.forEach { t ->
            tools.addView(Button(this).apply {
                text = t.label
                isAllCaps = false
                setOnClickListener {
                    tool = t
                    refresh()
                }
            })
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply { text = "되돌리기"; setOnClickListener { undo() } })
            addView(Button(context).apply { text = "저장"; setOnClickListener { save() } })
            addView(Button(context).apply { text = "지도 초기화"; setOnClickListener { confirmClear() } })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(context).apply { addView(tools) })
            addView(info)
            addView(view)
            addView(actions)
        })
        refresh()
    }

    /** 이미 등록된 작업대·장애물을 이 화면 위치로 옮겨 회색으로 보여준다. */
    private fun showExisting(g: GrayImage) {
        val m = meta ?: return
        val old = store.load()
        view.oldMarks = old.stations.map { s -> Triple((s.x - m.camX) / g.w, (s.y - m.camY) / g.h, s.name) }
        view.oldRects = old.obstacles.map { a ->
            val n = a.normalized()
            RectF((n.x0 - m.camX) / g.w, (n.y0 - m.camY) / g.h, (n.x1 - m.camX) / g.w, (n.y1 - m.camY) / g.h)
        }
    }

    /** 씨앗 상자를 누를 때마다 어떤 꽃인지 고른다. 상자 위 그림과 같은 꽃을 고르면 된다. */
    private fun askFlower(onPicked: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("방금 누른 씨앗 상자의 꽃")
            .setItems(Recipes.flowers.toTypedArray()) { _, i -> onPicked(Recipes.flowers[i]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refresh() {
        val current = when (val t = tool) {
            is Tool.Seed -> "씨앗 상자 (누르면 꽃 선택)"
            else -> t.label
        }
        val hint = if (tool is Tool.Obstacle) {
            "캐릭터가 지나갈 수 없는 카운터·테이블·벽을 손가락으로 끌어 사각형으로 칠하세요. 통로는 비워 두세요."
        } else {
            "작업대의 가운데를 누르세요. 씨앗 상자는 꽃마다 따로, 화분·포장대가 여러 개면 모두 등록하세요."
        }
        info.text = "선택: $current · 작업대 ${marks.size} · 장애물 ${rects.size}" +
            (if (charMark != null) " · 캐릭터 지정됨" else "") + "\n" + hint
        view.marks = marks.map { Triple(it.fx, it.fy, if (it.type == StationType.SEED) "${it.flower}" else it.type.label) } +
            listOfNotNull(charMark?.let { Triple(it.first, it.second, "캐릭터") })
        view.rects = rects.toList()
        view.invalidate()
    }

    private fun onTap(fx: Float, fy: Float) {
        when (val t = tool) {
            is Tool.Character -> {
                charMark = fx to fy
                history += "char"
            }
            is Tool.Seed -> {
                askFlower { flower ->
                    seedFlower = flower
                    Mark(StationType.SEED, flower, fx, fy).also { marks += it; history += it }
                    refresh()
                }
                return
            }
            is Tool.Other -> Mark(t.type, null, fx, fy).also { marks += it; history += it }
            is Tool.Obstacle -> return
        }
        refresh()
    }

    private fun addRect(r: RectF) {
        if (r.width() < 0.01f || r.height() < 0.01f) return
        rects += r
        history += r
        refresh()
    }

    private fun undo() {
        when (val last = history.removeLastOrNull()) {
            is Mark -> marks.remove(last)
            is RectF -> rects.remove(last)
            "char" -> charMark = null
        }
        refresh()
    }

    private fun save() {
        val g = gray ?: return
        val m = meta ?: return
        val old = store.load()
        if (old.stations.isEmpty() && charMark == null) {
            toast("처음 등록할 때는 '내 캐릭터'도 눌러 주세요")
            return
        }
        val patches = HashMap(store.loadPatches())
        val stations = old.stations.toMutableList()
        var nextId = (stations.maxOfOrNull { it.id } ?: 0) + 1
        var weak = 0
        for (mk in marks) {
            val cx = (mk.fx * g.w).toInt()
            val cy = (mk.fy * g.h).toInt()
            val st = Station(nextId++, mk.type, mk.flower, cx + m.camX, cy + m.camY)
            stations += st
            val patch = g.crop(cx, cy, MapStore.PATCH_HALF)
            if (patch != null && patch.std() >= 8f) patches[st.id] = patch else weak++
        }
        val obstacles = old.obstacles + rects.map { r ->
            Area(r.left * g.w + m.camX, r.top * g.h + m.camY, r.right * g.w + m.camX, r.bottom * g.h + m.camY)
        }
        val (fx, fy) = charMark ?: (old.charFx to old.charFy)
        store.save(stations, patches, obstacles, fx, fy)
        toast(
            "작업대 ${marks.size}곳 · 장애물 ${rects.size}개 저장 (전체 작업대 ${stations.size} · 장애물 ${obstacles.size})" +
                if (weak > 0) " · 화면 가장자리 $weak 곳은 위치 보정에 쓰지 않음" else "",
        )
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("등록한 지도(작업대·장애물)를 모두 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                store.clear()
                marks.clear(); rects.clear(); history.clear(); charMark = null
                view.oldMarks = emptyList(); view.oldRects = emptyList()
                refresh()
                toast("지도를 지웠습니다")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

@SuppressLint("ViewConstructor")
class MapView(ctx: Context, private val bitmap: Bitmap) : View(ctx) {
    var marks: List<Triple<Float, Float, String>> = emptyList()
    var rects: List<RectF> = emptyList()
    var oldMarks: List<Triple<Float, Float, String>> = emptyList()
    var oldRects: List<RectF> = emptyList()
    var onTap: ((Float, Float) -> Unit)? = null
    var onRect: ((RectF) -> Unit)? = null
    var dragMode: () -> Boolean = { false }

    private val dst = RectF()
    private var dragStart: Pair<Float, Float>? = null
    private var dragNow: Pair<Float, Float>? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW }
    private val oldRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.LTGRAY }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.YELLOW; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val oldText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = Color.LTGRAY; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val block = Paint().apply { color = Color.argb(110, 255, 40, 40) }
    private val oldBlock = Paint().apply { color = Color.argb(80, 160, 160, 160) }

    private fun toView(fx: Float, fy: Float) = (dst.left + fx * dst.width()) to (dst.top + fy * dst.height())
    private fun rectToView(r: RectF): RectF {
        val (l, t) = toView(r.left, r.top)
        val (rr, b) = toView(r.right, r.bottom)
        return RectF(l, t, rr, b)
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, dst, null)
        oldRects.forEach { canvas.drawRect(rectToView(it), oldBlock) }
        for ((fx, fy, label) in oldMarks) {
            val (x, y) = toView(fx, fy)
            canvas.drawCircle(x, y, 16f, oldRing)
            canvas.drawText(label, x + 20f, y + 28f, oldText)
        }
        rects.forEach { canvas.drawRect(rectToView(it), block) }
        val s = dragStart
        val n = dragNow
        if (s != null && n != null) canvas.drawRect(minOf(s.first, n.first), minOf(s.second, n.second), maxOf(s.first, n.first), maxOf(s.second, n.second), block)
        for ((fx, fy, label) in marks) {
            val (x, y) = toView(fx, fy)
            canvas.drawCircle(x, y, 20f, ring)
            canvas.drawText(label, x + 24f, y - 10f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(dst.left, dst.right)
        val y = e.y.coerceIn(dst.top, dst.bottom)
        if (dragMode()) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragStart = x to y; dragNow = x to y }
                MotionEvent.ACTION_MOVE -> dragNow = x to y
                MotionEvent.ACTION_UP -> {
                    val s = dragStart
                    if (s != null) {
                        val l = (minOf(s.first, x) - dst.left) / dst.width()
                        val t = (minOf(s.second, y) - dst.top) / dst.height()
                        val r = (maxOf(s.first, x) - dst.left) / dst.width()
                        val b = (maxOf(s.second, y) - dst.top) / dst.height()
                        onRect?.invoke(RectF(l, t, r, b))
                    }
                    dragStart = null; dragNow = null
                }
            }
            invalidate()
            return true
        }
        if (e.action == MotionEvent.ACTION_UP && dst.contains(e.x, e.y)) {
            onTap?.invoke((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height())
        }
        return true
    }
}
