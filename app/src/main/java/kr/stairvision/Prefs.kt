package kr.stairvision

import android.content.Context
import android.content.SharedPreferences
import kr.stairvision.analysis.AnalyzerParams
import kr.stairvision.analysis.Calibration
import kr.stairvision.analysis.GateConfig
import kr.stairvision.analysis.NPoint
import kr.stairvision.analysis.Side

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("stairvision", Context.MODE_PRIVATE)

    fun calibration(): Calibration? {
        if (!sp.contains("cal_color")) return null
        fun p(k: String) = NPoint(sp.getFloat("${k}_x", 0f), sp.getFloat("${k}_y", 0f))
        val c = Calibration(p("cur"), p("next"), p("climb"), p("turn"), sp.getInt("cal_color", 0))
        return c.takeIf { it.isValid() }
    }

    fun saveCalibration(c: Calibration) {
        val e = sp.edit()
        fun put(k: String, p: NPoint) {
            e.putFloat("${k}_x", p.x); e.putFloat("${k}_y", p.y)
        }
        put("cur", c.currentStep); put("next", c.nextStep)
        put("climb", c.climbButton); put("turn", c.turnButton)
        e.putInt("cal_color", c.stepColor)
        e.apply()
    }

    var initialFacing: Side
        get() = if (sp.getString("facing", "LEFT") == "RIGHT") Side.RIGHT else Side.LEFT
        set(v) = sp.edit().putString("facing", v.name).apply()

    var settleMs: Long
        get() = sp.getLong("settle_ms", 90)
        set(v) = sp.edit().putLong("settle_ms", v).apply()

    var requiredFrames: Int
        get() = sp.getInt("required_frames", 2)
        set(v) = sp.edit().putInt("required_frames", v).apply()

    var matchThreshold: Float
        get() = sp.getFloat("match_threshold", 0.6f)
        set(v) = sp.edit().putFloat("match_threshold", v).apply()

    var colorTolerance: Int
        get() = sp.getInt("color_tolerance", 40)
        set(v) = sp.edit().putInt("color_tolerance", v).apply()

    var minConfidence: Float
        get() = sp.getFloat("min_confidence", 0.4f)
        set(v) = sp.edit().putFloat("min_confidence", v).apply()

    fun analyzerParams() = AnalyzerParams(
        colorTolerance = colorTolerance,
        matchThreshold = matchThreshold,
    )

    fun gateConfig() = GateConfig(
        requiredFrames = requiredFrames.coerceAtLeast(1),
        settleMs = settleMs.coerceAtLeast(0),
        minConfidence = minConfidence,
    )

    companion object {
        const val CALIBRATION_FRAME = "calibration_frame.png"
    }
}
