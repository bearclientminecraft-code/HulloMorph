package com.chadmorph

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Контрольные точки деформации одного кадра: позиция, смещение (px, апрайт-кадр, Y вниз),
 * размеры компактного ядра вдоль/поперёк осей лица. Предвыделено, без аллокаций в build().
 */
class MorphField {
    companion object { const val MAX = 64 }

    var count = 0
    val cx = FloatArray(MAX)
    val cy = FloatArray(MAX)
    val dx = FloatArray(MAX)
    val dy = FloatArray(MAX)
    val sx = FloatArray(MAX)
    val sy = FloatArray(MAX)
    /** Ось «вправо по лицу» (учитывает roll). */
    var axisX = 1f
    var axisY = 0f

    fun clear() { count = 0 }
}

/**
 * Движок целевой геометрии. Вход: сглаженные landmarks (stride 3) + [MorphConfig] (+ [ReferenceProfile]).
 * Выход: [MorphField]. Сглаженное поле затем превращается в сетку ([DeformationMesh]) с защитой от складок.
 *
 * Что изменено относительно v0.2/v0.3-первой версии:
 *  - никаких «нарисованных» теней/бликов: определённость скул и впалость щёк — только геометрия;
 *  - параметры лево/право независимы; симметрия — отдельный параметр;
 *  - размеры «век» пропорциональны текущему раскрытию глаза → моргание и мимика сохраняются;
 *  - поза: смещения проецируются как плоский фронтальный патч (cos yaw/pitch), дальняя сторона гаснет;
 *  - все смещения по каждой точке суммируются в аккумулятор, поле — нормированное среднее (без суммарных складок);
 *  - временное сглаживание смещений (экспоненциальное, ~2 кадра).
 */
class FaceMorphEngine {
    val geo = FaceGeometry()
    private val metrics = FaceMetrics()
    private val nCtrl = LM.CTRL.size
    private val acc = FloatArray(nCtrl * 2)      // (a вдоль ex, b вдоль ey) в плоскости лица
    private val prev = FloatArray(nCtrl * 2)
    private var havePrev = false

    var lastYawDeg = 0f; private set
    var lastVisL = 1f; private set
    var lastVisR = 1f; private set

    fun reset() { havePrev = false }

    private fun add(id: Int, a: Float, b: Float) {
        val i = LM.CTRL_INDEX[id]
        acc[2 * i] += a
        acc[2 * i + 1] += b
    }

    private fun drive(param: Float, limit: Float, refAmount: Float, conf: Float): Float {
        val amt = if (refAmount.isNaN()) limit
        else conf * refAmount.coerceIn(-limit, limit) + (1f - conf) * limit
        return param * amt
    }

    private fun delta(target: Float, source: Float): Float =
        if (target.isNaN() || source.isNaN()) Float.NaN else target - source

    /**
     * @param envelope 0..1 — плавное появление/исчезновение при потере лица
     * @return true, если есть заметная деформация
     */
    fun build(
        pts: FloatArray, w: Int, h: Int,
        config: MorphConfig, envelope: Float, dtSec: Float,
        ref: ReferenceProfile?, field: MorphField
    ): Boolean {
        field.clear()
        val cfg = config.clamped()
        val lim = cfg.limits
        val k = envelope.coerceIn(0f, 1f) * cfg.strength
        if (k < 0.002f || cfg.isIdentity() || !geo.update(pts, w, h)) { havePrev = false; return false }
        val g = geo
        val s = g.scale

        // ---- поза ----
        val deg = (Math.PI / 180.0).toFloat()
        val yawDeg = g.yaw / deg
        val ay = abs(yawDeg)
        val pg = 1f - smoothstep(50f, 70f, ay)
        val pitg = 1f - smoothstep(30f, 50f, abs(g.pitch) / deg)
        val visL = (1f - smoothstep(12f, 40f, yawDeg)) * pg * pitg
        val visR = (1f - smoothstep(12f, 40f, -yawDeg)) * pg * pitg
        val conf = 1f - smoothstep(10f, 25f, ay)           // доверие 2D-метрикам референса
        val cosY = max(0.5f, cos(g.yaw))
        val cosP = max(0.5f, cos(g.pitch))
        lastYawDeg = yawDeg; lastVisL = visL; lastVisR = visR

        java.util.Arrays.fill(acc, 0f)
        val m = metrics
        val haveRef = ref != null
        if (haveRef) g.measure(m)

        for (side in 0..1) {
            val sp = if (side == 0) cfg.left else cfg.right
            val jaw = if (side == 0) LM.JAW_L else LM.JAW_R
            val gon = if (side == 0) 172 else 397
            val cheekC = if (side == 0) 205 else 425
            val contour = if (side == 0) 132 else 361
            val bone0 = if (side == 0) 116 else 345
            val bone1 = if (side == 0) 123 else 352
            val eye = if (side == 0) LM.EYE_L else LM.EYE_R
            val vis = if (side == 0) visL else visR
            val sg = if (side == 0) -1f else 1f
            val wgt = k * vis

            // Ширина челюсти + квадратизация
            val jRef = if (haveRef) delta(ref!!.jawHalf(side), m.jawHalf[side]) * g.halfB / s else Float.NaN
            val jamt = drive(sp.jaw, lim.jaw, jRef, conf) * s * wgt
            for (j in jaw.indices) {
                add(jaw[j], sg * jamt * LM.JAW_OUT[j], jamt * (lim.jawSquare / lim.jaw) * LM.JAW_SQUARE[j])
            }
            // Угол челюсти
            val gRef = if (haveRef) delta(m.gonial[side], ref!!.gonial(side)) * 0.5f * g.dist(jaw[0], jaw[1]) / s
            else Float.NaN
            val gamt = drive(sp.gonial, lim.gonial, gRef, conf) * s * wgt
            add(gon, sg * gamt * 0.7f, gamt * 0.7f)
            add(jaw[2], sg * gamt * 0.35f, gamt * 0.35f)
            // Впалые щёки (геометрия контура щеки внутрь)
            val hRef = if (haveRef) delta(m.cheek[side], ref!!.cheek(side)) * g.halfB / s else Float.NaN
            val hamt = drive(sp.hollow, lim.hollow, hRef, conf) * s * wgt
            add(cheekC, -sg * hamt, 0f)
            add(contour, -sg * hamt * 0.7f, 0f)
            // Скулы: подъём + вынос (только абсолютный режим)
            val bamt = sp.bone * lim.bone * s * wgt
            add(bone0, sg * bamt * 0.5f, -bamt * 0.8f)
            add(bone1, sg * bamt * 0.8f, -bamt * 0.5f)

            // Глаза
            val eyeW = g.dist(eye.outer, eye.inner)
            val gap = g.dist(eye.upperMid, eye.lowerMid)
            val tRef = if (haveRef) delta(ref!!.tilt(side), m.tilt[side]) * 0.65f else Float.NaN
            val tamt = drive(sp.tilt, lim.tilt, tRef, conf) * eyeW * wgt
            add(eye.outer, sg * tamt * 0.25f, -tamt)
            add(eye.inner, 0f, tamt * 0.35f)
            var need = Float.NaN
            if (haveRef) {
                val t = ref!!.open(side); val src = m.open[side]
                if (!t.isNaN() && !src.isNaN()) need = max(0f, (src - t) / max(src, 1e-3f))
            }
            val up = drive(sp.lidDrop, lim.lidDrop, if (need.isNaN()) Float.NaN else 0.65f * need, conf)
            val lo = drive(sp.lowerLid, lim.lowerLid, if (need.isNaN()) Float.NaN else 0.35f * need, conf)
            for (j in eye.upper.indices) add(eye.upper[j], 0f, up * gap * LM.LID_PROFILE[j] * wgt)
            for (j in eye.lower.indices) add(eye.lower[j], 0f, -lo * gap * LM.LID_PROFILE[j] * wgt)
        }

        // Подбородок
        val kc = k * pg * pitg
        val cRef = if (haveRef) delta(ref!!.target.chinHeight, m.chinHeight) * g.faceH / s else Float.NaN
        val camt = drive(cfg.chinProjection, lim.chinProjection, cRef, conf) * s * kc
        add(152, 0f, camt); add(175, 0f, camt * 0.5f); add(148, 0f, camt * 0.75f); add(377, 0f, camt * 0.75f)
        val wRef = if (haveRef) delta(ref!!.target.chinWidth, m.chinWidth) * g.halfB / s else Float.NaN
        val wamt = drive(cfg.chinWidth, lim.chinWidth, wRef, conf) * s * kc
        add(148, -wamt, 0f); add(377, wamt, 0f); add(176, -wamt * 0.5f, 0f); add(400, wamt * 0.5f, 0f)

        // Симметрия (только при почти анфас; пары — скелетные точки)
        if (cfg.symmetry > 0f) {
            val sw = cfg.symmetry * k * conf * pitg
            val cap = lim.symmetry * s
            for (p in LM.SYMMETRY_PAIRS) {
                val a = p[0]; val b = p[1]
                val ua = g.u(a) - g.u0; val ub = g.u(b) - g.u0
                val va = g.v(a); val vb = g.v(b)
                val hh = (abs(ua) + abs(ub)) * 0.5f
                val vm = (va + vb) * 0.5f
                symAdd(a, -hh - ua, vm - va, cap, sw)
                symAdd(b, hh - ub, vm - vb, cap, sw)
            }
        }

        // ---- в пиксели + проекция позы + временное сглаживание ----
        val alpha = 1f - exp(-dtSec.coerceIn(0.001f, 0.5f) / 0.06f)
        var maxAbs = 0f
        field.axisX = g.exx; field.axisY = g.exy
        for (i in 0 until nCtrl) {
            val a = acc[2 * i] * cosY
            val b = acc[2 * i + 1] * cosP
            var dx = a * g.exx + b * g.eyx
            var dy = a * g.exy + b * g.eyy
            if (havePrev) {
                dx = prev[2 * i] + alpha * (dx - prev[2 * i])
                dy = prev[2 * i + 1] + alpha * (dy - prev[2 * i + 1])
            }
            prev[2 * i] = dx; prev[2 * i + 1] = dy
            val id = LM.CTRL[i]
            field.cx[i] = g.px(id); field.cy[i] = g.py(id)
            field.dx[i] = dx; field.dy[i] = dy
            maxAbs = max(maxAbs, max(abs(dx), abs(dy)))
        }
        havePrev = true
        field.count = nCtrl
        fillKernels(field, g)
        return maxAbs > 0.05f
    }

    private fun symAdd(id: Int, a: Float, b: Float, cap: Float, sw: Float) {
        var aa = a; var bb = b
        val n = hypot(aa, bb)
        if (n > cap) { val f = cap / n; aa *= f; bb *= f }
        add(id, aa * sw, bb * sw)
    }

    private fun setK(field: MorphField, ids: IntArray, sx: Float, sy: Float) {
        for (id in ids) { val i = LM.CTRL_INDEX[id]; field.sx[i] = sx; field.sy[i] = sy }
    }

    private fun setK(field: MorphField, id: Int, sx: Float, sy: Float) {
        val i = LM.CTRL_INDEX[id]; field.sx[i] = sx; field.sy[i] = sy
    }

    private fun fillKernels(f: MorphField, g: FaceGeometry) {
        val s = g.scale
        for (id in LM.JAW_L) setK(f, id, 0.14f * s, 0.12f * s)
        for (id in LM.JAW_R) setK(f, id, 0.14f * s, 0.12f * s)
        setK(f, 152, 0.13f * s, 0.11f * s); setK(f, 175, 0.11f * s, 0.09f * s)
        setK(f, 148, 0.10f * s, 0.09f * s); setK(f, 377, 0.10f * s, 0.09f * s)
        setK(f, 205, 0.13f * s, 0.11f * s); setK(f, 425, 0.13f * s, 0.11f * s)
        setK(f, 132, 0.12f * s, 0.10f * s); setK(f, 361, 0.12f * s, 0.10f * s)
        setK(f, 116, 0.10f * s, 0.07f * s); setK(f, 345, 0.10f * s, 0.07f * s)
        setK(f, 123, 0.09f * s, 0.07f * s); setK(f, 352, 0.09f * s, 0.07f * s)
        for (id in intArrayOf(234, 454, 93, 323)) setK(f, id, 0.12f * s, 0.12f * s)
        for (eye in arrayOf(LM.EYE_L, LM.EYE_R)) {
            val eyeW = g.dist(eye.outer, eye.inner)
            val gap = g.dist(eye.upperMid, eye.lowerMid)
            setK(f, eye.outer, 0.45f * eyeW, 0.45f * eyeW)
            setK(f, eye.inner, 0.45f * eyeW, 0.45f * eyeW)
            val sy = max(0.9f * gap, 0.15f * eyeW)
            setK(f, eye.upper, 0.32f * eyeW, sy)
            setK(f, eye.lower, 0.32f * eyeW, sy)
        }
        for (id in intArrayOf(0, 17, 61, 291)) setK(f, id, 0.07f * s, 0.07f * s)
    }
}
