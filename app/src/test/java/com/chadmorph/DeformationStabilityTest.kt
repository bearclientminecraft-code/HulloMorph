package com.chadmorph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Тесты устойчивости деформации на СИНТЕТИЧЕСКОМ лице (см. SyntheticFace). Они проверяют математику
 * (отсутствие складок, непрерывность, независимость сторон, поведение при позе/моргании), но НЕ
 * качество на реальных лицах и НЕ работу MediaPipe/GL/MediaCodec.
 */
class DeformationStabilityTest {
    private val W = SyntheticFace.W
    private val H = SyntheticFace.H

    private fun run(
        pts: FloatArray, cfg: MorphConfig, ref: ReferenceProfile? = null,
        engine: FaceMorphEngine = FaceMorphEngine(), mesh: DeformationMesh = DeformationMesh()
    ): Triple<FaceMorphEngine, MorphField, DeformationMesh> {
        val field = MorphField()
        engine.build(pts, W, H, cfg, 1f, 1f / 30f, ref, field)
        mesh.update(field, W, H)
        return Triple(engine, field, mesh)
    }

    /** Независимая от DeformationMesh проверка площадей всех треугольников. */
    private fun assertNoFoldover(mesh: DeformationMesh, cfg: MorphConfig) {
        if (!mesh.active) return
        val cell = mesh.cellW * mesh.cellH
        val nx = mesh.nx
        for (i in 0 until mesh.ny - 1) for (j in 0 until nx - 1) {
            val a = i * nx + j; val b = a + 1; val c = a + nx; val d = c + 1
            for (t in intArrayOf(0, 1)) {
                val p = if (t == 0) a else b; val q = if (t == 0) b else d; val r = c
                val ratio = ((mesh.srcX[q] - mesh.srcX[p]) * (mesh.srcY[r] - mesh.srcY[p]) -
                    (mesh.srcX[r] - mesh.srcX[p]) * (mesh.srcY[q] - mesh.srcY[p])) / cell
                assertTrue("ratio $ratio < ${cfg.minAreaRatio} at cell ($i,$j)", ratio >= cfg.minAreaRatio - 1e-3f)
                assertTrue("ratio $ratio > ${cfg.maxAreaRatio} at cell ($i,$j)", ratio <= cfg.maxAreaRatio + 1e-3f)
            }
        }
    }

    @Test fun identityConfigProducesNoDeformation() {
        val (_, field, mesh) = run(SyntheticFace.make(), MorphConfig.OFF)
        assertFalse(mesh.active)
        assertEquals(0, field.count)
    }

    @Test fun extremeFrontalHasNoFoldoverOrExcessiveStretch() {
        val cfg = MorphConfig.EXTREME_HUNTER
        val (_, _, mesh) = run(SyntheticFace.make(), cfg)
        assertTrue(mesh.active)
        assertNoFoldover(mesh, cfg)
        assertTrue("limiter should keep some effect", mesh.minLambda > 0f)
    }

    @Test fun deformationVanishesOnRoiBorder() {
        val (_, _, mesh) = run(SyntheticFace.make(), MorphConfig.EXTREME_HUNTER)
        val nx = mesh.nx; val ny = mesh.ny
        for (k in 0 until nx) for (row in intArrayOf(0, ny - 1)) {
            val v = row * nx + k
            assertEquals(mesh.gx[v], mesh.srcX[v], 1e-3f); assertEquals(mesh.gy[v], mesh.srcY[v], 1e-3f)
        }
        for (k in 0 until ny) for (col in intArrayOf(0, nx - 1)) {
            val v = k * nx + col
            assertEquals(mesh.gx[v], mesh.srcX[v], 1e-3f); assertEquals(mesh.gy[v], mesh.srcY[v], 1e-3f)
        }
    }

    @Test fun poseSweepStaysFoldoverFree() {
        val cfg = MorphConfig.EXTREME_HUNTER
        for (yaw in intArrayOf(-60, -45, -30, -15, 0, 15, 30, 45, 60)) for (pitch in intArrayOf(-25, 0, 25)) for (roll in intArrayOf(-30, 0, 30)) {
            val (_, _, mesh) = run(SyntheticFace.make(yaw.toFloat(), pitch.toFloat(), roll.toFloat()), cfg)
            assertNoFoldover(mesh, cfg)
        }
    }

    @Test fun farSideFadesWhenHeadTurned() {
        val cfg = MorphConfig.EXTREME_HUNTER
        val (engine, field, _) = run(SyntheticFace.make(yawDeg = 45f), cfg)
        // Дальняя сторона (одна из двух) должна быть практически выключена, ближняя — активна.
        val far = if (engine.lastVisL < engine.lastVisR) 33 else 263
        val near = if (far == 33) 263 else 33
        val fi = LM.CTRL_INDEX[far]; val ni = LM.CTRL_INDEX[near]
        val farMag = hypot(field.dx[fi], field.dy[fi]); val nearMag = hypot(field.dx[ni], field.dy[ni])
        assertTrue("far=$farMag near=$nearMag", farMag < 0.2f * max(nearMag, 1f))
    }

    @Test fun poseEstimateTracksSyntheticYaw() {
        val geo = FaceGeometry()
        for (deg in intArrayOf(0, 15, 30)) {
            assertTrue(geo.update(SyntheticFace.make(yawDeg = deg.toFloat()), W, H))
            assertEquals(deg.toFloat(), abs(Math.toDegrees(geo.yaw.toDouble())).toFloat(), 3f)
        }
    }

    @Test fun leftAndRightAreIndependent() {
        val cfg = MorphConfig(left = SideParams(jaw = 1f, hollow = 1f, tilt = 1f, lidDrop = 1f))
        val (_, field, mesh) = run(SyntheticFace.make(), cfg)
        for (id in LM.JAW_R + LM.EYE_R.upper.toList() + LM.EYE_R.lower.toList() + intArrayOf(263, 362, 425, 361)) {
            val i = LM.CTRL_INDEX[id]
            assertEquals("right id $id dx", 0f, field.dx[i], 1e-4f)
            assertEquals("right id $id dy", 0f, field.dy[i], 1e-4f)
        }
        // Далеко справа от ядер левой стороны сетка не должна смещаться.
        for (v in 0 until mesh.vertexCount) if (mesh.gx[v] > 640f) {
            assertEquals(mesh.gx[v], mesh.srcX[v], 1e-3f); assertEquals(mesh.gy[v], mesh.srcY[v], 1e-3f)
        }
        val li = LM.CTRL_INDEX[172]
        assertTrue(hypot(field.dx[li], field.dy[li]) > 1f)
    }

    @Test fun bothSidesProduceMirroredJawDisplacement() {
        val cfg = MorphConfig(left = SideParams(jaw = 1f), right = SideParams(jaw = 1f))
        val (_, field, _) = run(SyntheticFace.make(), cfg)
        val l = LM.CTRL_INDEX[172]; val r = LM.CTRL_INDEX[397]
        assertEquals(-field.dx[l], field.dx[r], 0.05f)   // наружу в разные стороны
        assertEquals(field.dy[l], field.dy[r], 0.05f)
        assertTrue(field.dx[l] < -1f && field.dx[r] > 1f)
    }

    @Test fun eyelidDisplacementScalesWithOpening() {
        val cfg = MorphConfig(left = SideParams(lidDrop = 1f, lowerLid = 1f), right = SideParams(lidDrop = 1f, lowerLid = 1f))
        val (_, open, _) = run(SyntheticFace.make(eyeOpen = 1f), cfg)
        val (_, closed, _) = run(SyntheticFace.make(eyeOpen = 0.05f), cfg)
        val i = LM.CTRL_INDEX[159]
        val o = hypot(open.dx[i], open.dy[i]); val c = hypot(closed.dx[i], closed.dy[i])
        assertTrue("open=$o closed=$c", o > 1f && c < 0.15f * o)
    }

    @Test fun symmetryReducesPairAsymmetry() {
        val asym = mapOf(172 to (10f to 6f), 58 to (8f to 0f), 33 to (0f to -5f))
        val pts = SyntheticFace.make(asym = asym)
        fun pairError(cfg: MorphConfig): Float {
            val (engine, field, _) = run(pts, cfg)
            val g = engine.geo
            var e = 0f
            for (p in LM.SYMMETRY_PAIRS) {
                val ia = LM.CTRL_INDEX[p[0]]; val ib = LM.CTRL_INDEX[p[1]]
                val ax = field.cx[ia] + field.dx[ia]; val ay = field.cy[ia] + field.dy[ia]
                val bx = field.cx[ib] + field.dx[ib]; val by = field.cy[ib] + field.dy[ib]
                val ua = (ax - g.ox) * g.exx + (ay - g.oy) * g.exy - g.u0
                val ub = (bx - g.ox) * g.exx + (by - g.oy) * g.exy - g.u0
                val va = (ax - g.ox) * g.eyx + (ay - g.oy) * g.eyy
                val vb = (bx - g.ox) * g.eyx + (by - g.oy) * g.eyy
                e += abs(abs(ua) - abs(ub)) + abs(va - vb)
            }
            return e
        }
        val before = pairError(MorphConfig(symmetry = 1e-6f, strength = 1f).copy(symmetry = 0.0001f))
        val after = pairError(MorphConfig(symmetry = 1f))
        assertTrue("before=$before after=$after", after < 0.3f * before)
    }

    @Test fun referenceEqualToSourceGivesZeroDrivenDisplacement() {
        val pts = SyntheticFace.make()
        val geo = FaceGeometry(); geo.update(pts, W, H)
        val m = FaceMetrics(); geo.measure(m)
        val ref = ReferenceProfile(m, mirror = false)
        val p = SideParams(jaw = 1f, gonial = 1f, hollow = 1f, bone = 0f, tilt = 1f, lidDrop = 1f, lowerLid = 1f)
        val (_, field, _) = run(pts, MorphConfig(left = p, right = p, chinProjection = 1f, chinWidth = 1f), ref)
        for (i in 0 until field.count) {
            assertTrue("ctrl $i dx=${field.dx[i]} dy=${field.dy[i]}", abs(field.dx[i]) < 0.05f && abs(field.dy[i]) < 0.05f)
        }
    }

    @Test fun meshIsTranslationEquivariant() {
        val cfg = MorphConfig.STRONG
        val a = run(SyntheticFace.make(), cfg).third
        val shifted = SyntheticFace.make().also { for (id in LM.USED_IDS) it[LM.STRIDE * id] += 4f / W }
        val b = run(shifted, cfg).third
        for (v in 0 until a.vertexCount) {
            assertEquals(a.srcX[v] - a.gx[v], b.srcX[v] - b.gx[v], 0.05f)
            assertEquals(a.srcY[v] - a.gy[v], b.srcY[v] - b.gy[v], 0.05f)
        }
    }

    @Test fun sensitivityToLandmarkJitterIsBounded() {
        val cfg = MorphConfig.EXTREME_HUNTER
        val rng = Random(7)
        var worst = 0f
        repeat(12) {
            val a = run(SyntheticFace.make(jitterPx = 1.0f, rng = rng), cfg).third
            val b = run(SyntheticFace.make(jitterPx = 1.0f, rng = rng), cfg).third
            var qy = 700f
            while (qy <= 1180f) {
                var qx = 300f
                while (qx <= 780f) {
                    val (ax, ay) = sample(a, qx, qy); val (bx, by) = sample(b, qx, qy)
                    worst = max(worst, max(abs(ax - bx), abs(ay - by)))
                    qx += 12f
                }
                qy += 12f
            }
        }
        assertTrue("worst source shift $worst px for 1px landmark noise", worst < 8f)
    }

    @Test fun noNaNOrInfinityInVertices() {
        for (open in floatArrayOf(1f, 0.3f, 0.02f)) {
            val (_, _, mesh) = run(SyntheticFace.make(eyeOpen = open), MorphConfig.EXTREME_HUNTER)
            for (x in mesh.vertices) assertTrue(x.isFinite())
        }
    }

    @Test fun temporalSmoothingKeepsConsecutiveFramesClose() {
        val engine = FaceMorphEngine(); val mesh = DeformationMesh(); val field = MorphField()
        val cfg = MorphConfig.EXTREME_HUNTER
        val rng = Random(3)
        var prev: FloatArray? = null
        var worst = 0f
        repeat(40) {
            engine.build(SyntheticFace.make(jitterPx = 0.7f, rng = rng), W, H, cfg, 1f, 1f / 30f, null, field)
            mesh.update(field, W, H)
            val cur = FloatArray(mesh.vertexCount * 2) { n -> if (n % 2 == 0) mesh.srcX[n / 2] - mesh.gx[n / 2] else mesh.srcY[n / 2] - mesh.gy[n / 2] }
            prev?.let { p -> for (n in cur.indices) worst = max(worst, abs(cur[n] - p[n])) }
            prev = cur
        }
        // Сглаженное поле даёт <5 px; лимитер сетки добавляет свой плановый разгон Λ
        // (riseStep = 0.15 за кадр, см. DeformationMesh.update) — при |D| ≈ 45 px это ещё ~6.8 px.
        assertTrue("worst frame-to-frame displacement change $worst px", worst < 8f)
    }

    private fun sample(m: DeformationMesh, x: Float, y: Float): Pair<Float, Float> {
        val fx = ((x - m.roiX0) / m.cellW).coerceIn(0f, (m.nx - 1).toFloat() - 1e-3f)
        val fy = ((y - m.roiY0) / m.cellH).coerceIn(0f, (m.ny - 1).toFloat() - 1e-3f)
        val j = fx.toInt(); val i = fy.toInt(); val tx = fx - j; val ty = fy - i
        fun at(ii: Int, jj: Int, arr: FloatArray) = arr[ii * m.nx + jj]
        fun bil(arr: FloatArray) =
            (1 - ty) * ((1 - tx) * at(i, j, arr) + tx * at(i, j + 1, arr)) +
                ty * ((1 - tx) * at(i + 1, j, arr) + tx * at(i + 1, j + 1, arr))
        return bil(m.srcX) to bil(m.srcY)
    }
}
