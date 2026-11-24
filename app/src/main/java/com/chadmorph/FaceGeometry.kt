package com.chadmorph

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Система координат лица и оценка позы по landmarks (без выделений памяти в update()).
 *
 * ОГРАНИЧЕНИЕ: это НЕ реконструкция 3D-лица. Roll берётся из линии глаз (2D). Yaw/pitch — приближённая
 * оценка по z-координатам MediaPipe (глубина, предсказанная моделью, масштаб как у x) плюс 2D-оценка
 * по положению носа; берётся наибольшая по модулю. Эффекты затем масштабируются как для плоского
 * фронтального патча (cos yaw/pitch) и гасятся на дальней стороне.
 */
class FaceGeometry {
    private var pts = FloatArray(0)
    private var w = 1
    private var h = 1

    var valid = false; private set
    var exx = 1f; private set
    var exy = 0f; private set
    var eyx = 0f; private set
    var eyy = 1f; private set
    var ox = 0f; private set
    var oy = 0f; private set
    var faceH = 1f; private set
    var scale = 1f; private set
    var u0 = 0f; private set
    var halfB = 1f; private set
    var yaw = 0f; private set     // рад; + = левая (на изображении) сторона дальше от камеры
    var pitch = 0f; private set   // рад

    fun px(i: Int) = pts[LM.STRIDE * i] * w
    fun py(i: Int) = pts[LM.STRIDE * i + 1] * h
    fun pz(i: Int) = pts[LM.STRIDE * i + 2] * w
    fun u(i: Int) = (px(i) - ox) * exx + (py(i) - oy) * exy
    fun v(i: Int) = (px(i) - ox) * eyx + (py(i) - oy) * eyy
    fun dist(a: Int, b: Int) = hypot(px(a) - px(b), py(a) - py(b))

    fun update(points: FloatArray, width: Int, height: Int): Boolean {
        pts = points; w = width; h = height
        valid = false
        val ex = px(263) - px(33)
        val ey = py(263) - py(33)
        val el = hypot(ex, ey)
        if (el < 4f) return false
        exx = ex / el; exy = ey / el
        eyx = -exy; eyy = exx
        ox = (px(133) + px(362)) * 0.5f
        oy = (py(133) + py(362)) * 0.5f
        faceH = hypot(px(152) - px(10), py(152) - py(10))
        if (faceH < 16f) return false
        scale = 0.75f * faceH
        u0 = (u(10) + u(168) + u(152)) / 3f
        halfB = (u(454) - u(234)) * 0.5f
        if (halfB < 8f) return false

        val du = abs(u(454) - u(234))
        val yaw3 = atan2(pz(234) - pz(454), du)
        val t = (u(1) - u(234)) / (u(454) - u(234))
        val yaw2 = asin(((0.5f - t) * 2f * 0.9f).coerceIn(-1f, 1f))
        yaw = if (abs(yaw3) >= abs(yaw2)) yaw3 else yaw2
        pitch = atan2(pz(10) - pz(152), abs(v(152) - v(10)))
        valid = true
        return true
    }

    private fun angleAt(c: Int, a: Int, b: Int): Float {
        val ax = px(a) - px(c); val ay = py(a) - py(c)
        val bx = px(b) - px(c); val by = py(b) - py(c)
        val d = sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)
        if (d < 1e-3f) return Float.NaN
        return acos(((ax * bx + ay * by) / d).coerceIn(-1f, 1f))
    }

    /** Измеряет пропорции текущего кадра. Требует valid == true. */
    fun measure(out: FaceMetrics) {
        for (side in 0..1) {
            val jaw = if (side == 0) 172 else 397
            val g1 = if (side == 0) 58 else 288
            val g2 = if (side == 0) 136 else 365
            val ch = if (side == 0) 132 else 361
            val eye = if (side == 0) LM.EYE_L else LM.EYE_R
            out.jawHalf[side] = abs(u(jaw) - u0) / halfB
            out.gonial[side] = angleAt(jaw, g1, g2)
            out.cheek[side] = abs(u(ch) - u0) / halfB
            out.tilt[side] = atan2(v(eye.inner) - v(eye.outer), abs(u(eye.outer) - u(eye.inner)))
            val eyeW = dist(eye.outer, eye.inner)
            out.open[side] = if (eyeW > 1f) dist(eye.upperMid, eye.lowerMid) / eyeW else Float.NaN
        }
        out.chinHeight = dist(17, 152) / faceH
        out.chinWidth = dist(148, 377) / (2f * halfB)
    }
}
