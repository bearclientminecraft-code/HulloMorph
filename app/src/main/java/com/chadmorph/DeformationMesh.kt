package com.chadmorph

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Регулярная сетка nx×ny поверх ROI лица. Для каждой вершины g (позиция в выходном кадре) считается
 * обратное отображение src = g − Λ·D(g), где D — нормированное среднее компактных ядер из [MorphField].
 * GPU рисует сетку с texcoord = src (кусочно-аффинно, без шейдерных циклов).
 *
 * Защита от складок/растяжения: для каждой ячейки находится максимальный масштаб, при котором отношение
 * площади треугольников (источник/назначение) лежит в [minRatio, maxRatio]; Λ — минимум по смежным ячейкам,
 * затем сглаживается; в конце финальная проверка (глобальный множитель при необходимости) гарантирует,
 * что отрисованные треугольники не перевёрнуты и не растянуты сильнее предела. Все массивы предвыделены.
 */
class DeformationMesh(
    val nx: Int = 64, val ny: Int = 64,
    var minRatio: Float = 0.35f, var maxRatio: Float = 2.5f
) {
    val vertexCount = nx * ny
    private val cellCount = (nx - 1) * (ny - 1)

    val gx = FloatArray(vertexCount)
    val gy = FloatArray(vertexCount)
    val srcX = FloatArray(vertexCount)
    val srcY = FloatArray(vertexCount)
    private val dX = FloatArray(vertexCount)
    private val dY = FloatArray(vertexCount)
    val lambda = FloatArray(vertexCount)
    private val lambdaPrev = FloatArray(vertexCount) { 1f }
    private val tmp = FloatArray(vertexCount)
    private val cellLam = FloatArray(cellCount)
    private val cellLo = FloatArray(cellCount)
    private val cellHi = FloatArray(cellCount)
    private val rmax = FloatArray(MorphField.MAX)

    /** x, y (NDC), u, v (апрайт-UV, v вверх) — готово к загрузке в GL. */
    val vertices = FloatArray(vertexCount * 4)
    val indices = ShortArray(cellCount * 6)

    var active = false; private set
    var roiX0 = 0f; private set
    var roiY0 = 0f; private set
    var cellW = 1f; private set
    var cellH = 1f; private set
    var minLambda = 1f; private set
    var minAreaRatioSeen = 1f; private set
    var maxAreaRatioSeen = 1f; private set

    init {
        var n = 0
        for (i in 0 until ny - 1) for (j in 0 until nx - 1) {
            val v00 = i * nx + j; val v01 = v00 + 1; val v10 = v00 + nx; val v11 = v10 + 1
            indices[n++] = v00.toShort(); indices[n++] = v01.toShort(); indices[n++] = v10.toShort()
            indices[n++] = v01.toShort(); indices[n++] = v11.toShort(); indices[n++] = v10.toShort()
        }
    }

    private fun resetState() {
        active = false
        java.util.Arrays.fill(lambdaPrev, 1f)
    }

    /** @param riseStep максимальный рост Λ за кадр (падение мгновенное) — против мерцания лимитера */
    fun update(field: MorphField, w: Int, h: Int, riseStep: Float = 0.15f): Boolean {
        if (field.count == 0) { resetState(); return false }
        var maxD = 0f
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (k in 0 until field.count) {
            val r = max(field.sx[k], field.sy[k]) * 1.02f + 2f
            rmax[k] = r
            x0 = min(x0, field.cx[k] - r); x1 = max(x1, field.cx[k] + r)
            y0 = min(y0, field.cy[k] - r); y1 = max(y1, field.cy[k] + r)
            maxD = max(maxD, max(abs(field.dx[k]), abs(field.dy[k])))
        }
        if (maxD < 0.05f) { resetState(); return false }
        x0 = max(0f, x0); y0 = max(0f, y0); x1 = min((w - 1).toFloat(), x1); y1 = min((h - 1).toFloat(), y1)
        if (x1 - x0 < 8f || y1 - y0 < 8f) { resetState(); return false }

        cellW = (x1 - x0) / (nx - 1); cellH = (y1 - y0) / (ny - 1)
        roiX0 = x0; roiY0 = y0
        val exx = field.axisX; val exy = field.axisY
        val eyx = -exy; val eyy = exx

        // 1) поле D в вершинах
        for (i in 0 until ny) for (j in 0 until nx) {
            val v = i * nx + j
            val vx = x0 + cellW * j; val vy = y0 + cellH * i
            gx[v] = vx; gy[v] = vy
            var sdx = 0f; var sdy = 0f; var ws = 0f
            for (k in 0 until field.count) {
                val ax = vx - field.cx[k]
                val r = rmax[k]
                if (ax > r || ax < -r) continue
                val ay = vy - field.cy[k]
                if (ay > r || ay < -r) continue
                val qx = (ax * exx + ay * exy) / field.sx[k]
                val qy = (ax * eyx + ay * eyy) / field.sy[k]
                val r2 = qx * qx + qy * qy
                if (r2 >= 1f) continue
                val t = 1f - r2
                val wt = t * t * (3f - 2f * t)
                sdx += wt * field.dx[k]; sdy += wt * field.dy[k]; ws += wt
            }
            val n = if (ws > 1f) ws else 1f
            dX[v] = sdx / n; dY[v] = sdy / n
        }

        // 2) безопасный масштаб на ячейку (непрерывен по D)
        val cellArea = cellW * cellH
        for (i in 0 until ny - 1) for (j in 0 until nx - 1) {
            val v00 = i * nx + j
            val c = i * (nx - 1) + j
            if (cellOk(v00, 1f, cellArea)) { cellLam[c] = 1f } else {
                var lo = 0f; var hi = 1f
                for (it in 0 until 10) {
                    val mid = (lo + hi) * 0.5f
                    if (cellOk(v00, mid, cellArea)) lo = mid else hi = mid
                }
                cellLam[c] = lo
            }
        }
        // 3) Λ в вершине = минимум по смежным ячейкам
        for (i in 0 until ny) for (j in 0 until nx) {
            var m = 1f
            for (di in -1..0) for (dj in -1..0) {
                val ci = i + di; val cj = j + dj
                if (ci < 0 || cj < 0 || ci >= ny - 1 || cj >= nx - 1) continue
                m = min(m, cellLam[ci * (nx - 1) + cj])
            }
            lambda[i * nx + j] = m
        }
        // 4) сглаживание провалов (min с 3×3 средним), 3 прохода
        repeat(3) {
            for (i in 0 until ny) for (j in 0 until nx) {
                var sum = 0f
                for (di in -1..1) for (dj in -1..1) {
                    val ii = (i + di).coerceIn(0, ny - 1); val jj = (j + dj).coerceIn(0, nx - 1)
                    sum += lambda[ii * nx + jj]
                }
                tmp[i * nx + j] = min(lambda[i * nx + j], sum / 9f)
            }
            System.arraycopy(tmp, 0, lambda, 0, vertexCount)
        }
        // 5) временное ограничение роста
        for (v in 0 until vertexCount) lambda[v] = min(lambda[v], lambdaPrev[v] + riseStep)
        // 6) финальная гарантия
        if (!allOk(1f, cellArea)) {
            var lo = 0f; var hi = 1f
            for (it in 0 until 14) {
                val mid = (lo + hi) * 0.5f
                if (allOk(mid, cellArea)) lo = mid else hi = mid
            }
            for (v in 0 until vertexCount) lambda[v] *= lo
        }
        System.arraycopy(lambda, 0, lambdaPrev, 0, vertexCount)

        // 7) вершины для GL + статистика
        minLambda = 1f
        for (v in 0 until vertexCount) {
            val l = lambda[v]
            minLambda = min(minLambda, l)
            val sx = gx[v] - l * dX[v]; val sy = gy[v] - l * dY[v]
            srcX[v] = sx; srcY[v] = sy
            vertices[4 * v] = gx[v] / w * 2f - 1f
            vertices[4 * v + 1] = 1f - gy[v] / h * 2f
            vertices[4 * v + 2] = sx / w
            vertices[4 * v + 3] = 1f - sy / h
        }
        computeRatioStats(cellArea)
        active = true
        return true
    }

    /** Отношение площади (источник/назначение) двух треугольников ячейки при масштабе lam (на все 4 вершины). */
    private fun cellOk(v00: Int, lam: Float, cellArea: Float): Boolean {
        val v01 = v00 + 1; val v10 = v00 + nx; val v11 = v10 + 1
        return triOk(v00, v01, v10, lam, lam, lam, cellArea) && triOk(v01, v11, v10, lam, lam, lam, cellArea)
    }

    private fun triOk(a: Int, b: Int, c: Int, la: Float, lb: Float, lc: Float, cellArea: Float): Boolean {
        val ax = gx[a] - la * dX[a]; val ay = gy[a] - la * dY[a]
        val bx = gx[b] - lb * dX[b]; val by = gy[b] - lb * dY[b]
        val cx = gx[c] - lc * dX[c]; val cy = gy[c] - lc * dY[c]
        val ratio = ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / cellArea
        return ratio >= minRatio && ratio <= maxRatio
    }

    /** Проверка всех ячеек с фактическими Λ вершин, дополнительно умноженными на g. */
    private fun allOk(g: Float, cellArea: Float): Boolean {
        for (i in 0 until ny - 1) for (j in 0 until nx - 1) {
            val v00 = i * nx + j; val v01 = v00 + 1; val v10 = v00 + nx; val v11 = v10 + 1
            if (!triOk(v00, v01, v10, g * lambda[v00], g * lambda[v01], g * lambda[v10], cellArea)) return false
            if (!triOk(v01, v11, v10, g * lambda[v01], g * lambda[v11], g * lambda[v10], cellArea)) return false
        }
        return true
    }

    private fun computeRatioStats(cellArea: Float) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until ny - 1) for (j in 0 until nx - 1) {
            val v00 = i * nx + j; val v01 = v00 + 1; val v10 = v00 + nx; val v11 = v10 + 1
            for (t in 0..1) {
                val a = if (t == 0) v00 else v01
                val b = if (t == 0) v01 else v11
                val c = v10
                val r = ((srcX[b] - srcX[a]) * (srcY[c] - srcY[a]) - (srcX[c] - srcX[a]) * (srcY[b] - srcY[a])) / cellArea
                mn = min(mn, r); mx = max(mx, r)
            }
        }
        minAreaRatioSeen = mn; maxAreaRatioSeen = mx
    }
}
