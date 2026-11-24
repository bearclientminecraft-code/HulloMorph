package com.chadmorph

import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Синтетическое «лицо» для тестов: ортографическая проекция эллипсоида с 3D-позой.
 * НЕ реальные landmarks MediaPipe — только проверка устойчивости математики.
 * Кадр 1080x1920, лицо ~700 px высотой.
 */
object SyntheticFace {
    const val W = 1080
    const val H = 1920
    const val CX = 540f
    private const val CY = 820f

    // id (слева на изображении) -> (x, y); правая сторона зеркалится относительно CX.
    private val LEFT = mapOf(
        33 to (355f to 764f), 133 to (458f to 768f),
        246 to (364f to 758f), 161 to (380f to 753f), 160 to (395f to 750f), 159 to (407f to 749f),
        158 to (420f to 751f), 157 to (436f to 755f), 173 to (449f to 761f),
        7 to (366f to 769f), 163 to (381f to 776f), 144 to (396f to 780f), 145 to (407f to 781f),
        153 to (420f to 780f), 154 to (437f to 776f), 155 to (449f to 771f),
        234 to (277f to 830f), 93 to (285f to 920f), 132 to (300f to 985f), 58 to (325f to 1040f),
        172 to (365f to 1090f), 136 to (410f to 1130f), 150 to (450f to 1155f), 149 to (485f to 1170f),
        176 to (510f to 1178f), 148 to (525f to 1182f),
        205 to (360f to 960f), 116 to (330f to 835f), 123 to (310f to 860f), 61 to (462f to 1012f)
    )
    private val RIGHT_OF = mapOf(
        33 to 263, 133 to 362, 246 to 466, 161 to 388, 160 to 387, 159 to 386, 158 to 385, 157 to 384,
        173 to 398, 7 to 249, 163 to 390, 144 to 373, 145 to 374, 153 to 380, 154 to 381, 155 to 382,
        234 to 454, 93 to 323, 132 to 361, 58 to 288, 172 to 397, 136 to 365, 150 to 379, 149 to 378,
        176 to 400, 148 to 377, 205 to 425, 116 to 345, 123 to 352, 61 to 291
    )
    private val CENTER = mapOf(
        10 to (CX to 480f), 168 to (CX to 740f), 1 to (CX to 905f), 0 to (CX to 985f),
        17 to (CX to 1040f), 199 to (CX to 1095f), 175 to (CX to 1140f), 152 to (CX to 1185f)
    )
    private val UPPER_L = intArrayOf(246, 161, 160, 159, 158, 157, 173)
    private val LOWER_L = intArrayOf(7, 163, 144, 145, 153, 154, 155)

    /**
     * @param eyeOpen 1 = норма, ~0 = глаза закрыты (веки сходятся к средней линии)
     * @param asym смещения (dx, dy) в пикселях для отдельных landmarks (асимметрия лица)
     */
    fun make(
        yawDeg: Float = 0f, pitchDeg: Float = 0f, rollDeg: Float = 0f,
        jitterPx: Float = 0f, rng: Random? = null, eyeOpen: Float = 1f,
        asym: Map<Int, Pair<Float, Float>> = emptyMap()
    ): FloatArray {
        val base = HashMap<Int, Pair<Float, Float>>()
        for ((k, p) in LEFT) {
            base[k] = p
            base[RIGHT_OF.getValue(k)] = (2 * CX - p.first) to p.second
        }
        for ((k, p) in CENTER) base[k] = p
        if (eyeOpen != 1f) {
            val ymid = (LEFT.getValue(159).second + LEFT.getValue(145).second) / 2f
            for (id in UPPER_L + LOWER_L) {
                for (target in intArrayOf(id, RIGHT_OF.getValue(id))) {
                    val p = base.getValue(target)
                    base[target] = p.first to (ymid + (p.second - ymid) * eyeOpen)
                }
            }
        }
        val out = FloatArray(LM.COUNT * LM.STRIDE)
        val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val roll = Math.toRadians(rollDeg.toDouble()).toFloat()
        for ((id, p0) in base) {
            var x = p0.first; var y = p0.second
            asym[id]?.let { x += it.first; y += it.second }
            val a = 330f; val b = 440f
            val r = 1f - ((x - CX) / a) * ((x - CX) / a) - ((y - CY) / b) * ((y - CY) / b)
            var z = -110f * sqrt(max(r, 0f))
            var X = x - CX; var Y = y - CY
            var c = cos(yaw); var s = sin(yaw)
            val X1 = X * c + z * s; val Z1 = -X * s + z * c; X = X1; z = Z1
            c = cos(pitch); s = sin(pitch)
            val Y1 = Y * c - z * s; val Z2 = Y * s + z * c; Y = Y1; z = Z2
            c = cos(roll); s = sin(roll)
            val X2 = X * c - Y * s; val Y2 = X * s + Y * c; X = X2; Y = Y2
            X += CX; Y += CY
            if (jitterPx > 0f && rng != null) {
                X += (rng.nextGaussian() * jitterPx).toFloat(); Y += (rng.nextGaussian() * jitterPx).toFloat()
            }
            out[LM.STRIDE * id] = X / W
            out[LM.STRIDE * id + 1] = Y / H
            out[LM.STRIDE * id + 2] = z / W
        }
        return out
    }
}
