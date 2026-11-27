package kr.stairvision.zombie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 학습한 지도(작업대 위치·모습, 캐릭터 화면 위치)를 앱 내부 저장소에 보관한다. */
class MapStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val mapFile = File(dir, "zombie_map.json")
    val captureImage = File(dir, "zombie_capture.png")
    private val captureMeta = File(dir, "zombie_capture.json")
    private val prefs = context.applicationContext.getSharedPreferences("zombie", Context.MODE_PRIVATE)

    data class MapData(
        val stations: List<Station>,
        val landmarks: List<Landmark>,
        val obstacles: List<Area>,
        val charFx: Float,
        val charFy: Float,
    )

    data class CaptureMeta(val camX: Float, val camY: Float, val cell: Int)

    fun load(): MapData {
        if (!mapFile.exists()) return MapData(emptyList(), emptyList(), emptyList(), 0.5f, 0.52f)
        val o = JSONObject(mapFile.readText())
        val st = ArrayList<Station>()
        val lm = ArrayList<Landmark>()
        val arr = o.getJSONArray("stations")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val station = Station(
                s.getInt("id"), StationType.valueOf(s.getString("type")),
                if (s.has("flower")) s.getString("flower") else null,
                s.getDouble("x").toFloat(), s.getDouble("y").toFloat(),
            )
            st += station
            if (s.has("patch")) {
                val p = s.getJSONArray("patch")
                val size = s.getInt("patchSize")
                val px = IntArray(p.length()) { p.getInt(it) }
                lm += Landmark(station.id, station.x, station.y, GrayImage(size, size, px))
            }
        }
        val obs = ArrayList<Area>()
        o.optJSONArray("obstacles")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                obs += Area(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
            }
        }
        return MapData(st, lm, obs, o.optDouble("charFx", 0.5).toFloat(), o.optDouble("charFy", 0.52).toFloat())
    }

    fun save(stations: List<Station>, patches: Map<Int, GrayImage>, obstacles: List<Area>, charFx: Float, charFy: Float) {
        val arr = JSONArray()
        for (s in stations) {
            val j = JSONObject()
                .put("id", s.id).put("type", s.type.name)
                .put("x", s.x.toDouble()).put("y", s.y.toDouble())
            s.flower?.let { j.put("flower", it) }
            patches[s.id]?.let { p ->
                j.put("patchSize", p.w)
                j.put("patch", JSONArray().apply { p.px.forEach { put(it) } })
            }
            arr.put(j)
        }
        mapFile.writeText(
            JSONObject().put("stations", arr)
                .put("obstacles", JSONArray().apply {
                    obstacles.forEach { a ->
                        put(JSONArray().put(a.x0.toDouble()).put(a.y0.toDouble()).put(a.x1.toDouble()).put(a.y1.toDouble()))
                    }
                })
                .put("charFx", charFx.toDouble()).put("charFy", charFy.toDouble()).toString(),
        )
        bumpVersion()
    }

    /** 기존 지도에 이어 붙일 때 쓰는 작업대별 조각. */
    fun loadPatches(): Map<Int, GrayImage> = load().landmarks.associate { it.id to it.patch }

    fun clear() {
        mapFile.delete()
        bumpVersion()
    }

    fun saveCaptureMeta(m: CaptureMeta) {
        captureMeta.writeText(
            JSONObject().put("camX", m.camX.toDouble()).put("camY", m.camY.toDouble()).put("cell", m.cell).toString(),
        )
    }

    fun loadCaptureMeta(): CaptureMeta? {
        if (!captureMeta.exists() || !captureImage.exists()) return null
        val o = JSONObject(captureMeta.readText())
        return CaptureMeta(o.getDouble("camX").toFloat(), o.getDouble("camY").toFloat(), o.getInt("cell"))
    }

    private fun bumpVersion() = prefs.edit().putInt(VERSION_KEY, prefs.getInt(VERSION_KEY, 0) + 1).apply()

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        private const val VERSION_KEY = "map_version"

        /** 원본 프레임(화면의 절반 해상도) 몇 픽셀을 한 칸으로 묶는가. */
        const val CELL = 4

        /** 지형지물 조각 반지름(칸). */
        const val PATCH_HALF = 8
    }
}
