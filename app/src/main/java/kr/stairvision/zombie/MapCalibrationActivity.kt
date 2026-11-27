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
 * 저장한 게임 화면 위에서 작업대를 눌러 지도에 등록한다.
 * 여러 번 나눠 등록해도 된다: 캡처마다 그때의 카메라 위치가 함께 저장되므로 같은 지도에 이어 붙는다.
 */
class MapCalibrationActivity : Activity() {

    private lateinit var store: MapStore
    private lateinit var view: MapView
    private lateinit var info: TextView
    private var meta: MapStore.CaptureMeta? = null
    private var gray: GrayImage? = null
    private var bitmap: Bitmap? = null

    private var tool: Tool = Tool.Seed
    private var seedFlower: String = Recipes.flowers.first()
    private val added = mutableListOf<Mark>()
    private var charMark: Pair<Float, Float>? = null

    private sealed class Tool(val label: String) {
        data object Character : Tool("내 캐릭터")
        data object Seed : Tool("씨앗 상자")
        data class Other(val type: StationType) : Tool(type.label)
    }

    /** 화면에 찍은 점. fx, fy는 캡처 이미지 비율 좌표. */
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
        bitmap = bmp
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        gray = GrayImage.from(IntArraySource(px, bmp.width, bmp.height), meta!!.cell)

        val existing = store.load()
        info = TextView(this).apply { textSize = 13f; setPadding(16, 6, 16, 6) }
        view = MapView(this, bmp).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onTap = { fx, fy -> onTap(fx, fy) }
            existingCount = existing.stations.size
        }

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val all = listOf(Tool.Character, Tool.Seed) +
            listOf(StationType.POT, StationType.BRAZIER, StationType.BOOTH, StationType.WRAP, StationType.BAG).map { Tool.Other(it) }
        all.forEach { t ->
            tools.addView(Button(this).apply {
                text = t.label
                isAllCaps = false
                setOnClickListener {
                    tool = t
                    if (t is Tool.Seed) pickFlower() else refresh()
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

    private fun pickFlower() {
        AlertDialog.Builder(this)
            .setTitle("이 씨앗 상자의 꽃")
            .setItems(Recipes.flowers.toTypedArray()) { _, i ->
                seedFlower = Recipes.flowers[i]
                refresh()
            }
            .show()
    }

    private fun refresh() {
        val current = when (val t = tool) {
            is Tool.Seed -> "씨앗 상자($seedFlower)"
            else -> t.label
        }
        info.text = "선택: $current · 이번에 찍은 곳 ${added.size}개" +
            (if (charMark != null) " · 캐릭터 지정됨" else "") +
            "\n작업대의 가운데를 누르세요. 씨앗 상자는 꽃마다 따로, 화분·포장대가 여러 개면 모두 등록하세요."
        view.marks = added.map { Triple(it.fx, it.fy, label(it)) } +
            listOfNotNull(charMark?.let { Triple(it.first, it.second, "캐릭터") })
        view.invalidate()
    }

    private fun label(m: Mark) = if (m.type == StationType.SEED) "${m.flower}" else m.type.label

    private fun onTap(fx: Float, fy: Float) {
        when (val t = tool) {
            is Tool.Character -> charMark = fx to fy
            is Tool.Seed -> added += Mark(StationType.SEED, seedFlower, fx, fy)
            is Tool.Other -> added += Mark(t.type, null, fx, fy)
        }
        refresh()
    }

    private fun undo() {
        if (added.isNotEmpty()) added.removeAt(added.lastIndex) else charMark = null
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
        for (mk in added) {
            val cx = (mk.fx * g.w).toInt()
            val cy = (mk.fy * g.h).toInt()
            val st = Station(nextId++, mk.type, mk.flower, cx + m.camX, cy + m.camY)
            stations += st
            val patch = g.crop(cx, cy, MapStore.PATCH_HALF)
            if (patch != null && patch.std() >= 8f) patches[st.id] = patch else weak++
        }
        val (fx, fy) = charMark ?: (old.charFx to old.charFy)
        store.save(stations, patches, fx, fy)
        toast("작업대 ${added.size}곳을 저장했습니다 (전체 ${stations.size}곳)" + if (weak > 0) " · 화면 가장자리 $weak 곳은 위치 보정에 쓰지 않음" else "")
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("등록한 지도를 모두 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                store.clear()
                added.clear(); charMark = null
                view.existingCount = 0
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
    var existingCount = 0
    var onTap: ((Float, Float) -> Unit)? = null
    private val dst = RectF()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; color = Color.YELLOW; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, dst, null)
        for ((fx, fy, label) in marks) {
            val x = dst.left + fx * dst.width()
            val y = dst.top + fy * dst.height()
            canvas.drawCircle(x, y, 20f, ring)
            canvas.drawText(label, x + 24f, y - 10f, text)
        }
        if (existingCount > 0) canvas.drawText("이미 등록된 작업대 $existingCount 곳", dst.left + 12f, dst.top + 36f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && dst.contains(e.x, e.y)) {
            onTap?.invoke((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height())
        }
        return true
    }
}
