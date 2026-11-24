package com.chadmorph

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One Euro filter: сглаживает дрожание в покое, почти не даёт задержки при движении. */
internal class OneEuro(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float = 1f
) {
    private var has = false
    private var xPrev = 0f
    private var dxPrev = 0f

    fun reset() { has = false }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, dt: Float): Float {
        if (!has) {
            has = true; xPrev = x; dxPrev = 0f
            return x
        }
        val dx = (x - xPrev) / dt
        val aD = alpha(dt, dCutoff)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(dt, cutoff)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat; dxPrev = dxHat
        return xHat
    }
}

/**
 * MediaPipe Face Landmarker (VIDEO mode, офлайн-модель из assets) + временное сглаживание.
 * Хранит сглаженные x,y,z нужных landmarks в [pts] (stride [LM.STRIDE]).
 * Вызывать строго из одного потока, кадры — с неубывающими timestamp.
 */
class FaceTracker(context: Context) : AutoCloseable {

    private val landmarker: FaceLandmarker
    private val ids = LM.USED_IDS
    private val filters = Array(ids.size * 3) { OneEuro(minCutoff = 2.0f, beta = 15f) }

    val pts = FloatArray(LM.COUNT * LM.STRIDE)

    private var lastTsMs = -1L
    private var havePts = false
    private var lostFrames = 0
    private var needReset = false
    private var envelope = 0f

    /** Сглаженная «огибающая» 0..1: плавно появляется при обнаружении и гаснет при потере лица. */
    var strength = 0f; private set
    /** Время между кадрами, с (для временных фильтров движка). */
    var dtSec = 1f / 30f; private set
    var framesSeen = 0; private set
    var framesDetected = 0; private set

    init {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, opts)
    }

    /** @return true, если есть лицо (свежее или удерживаемое при коротком пропуске) */
    fun update(frame: Bitmap, ptsUs: Long): Boolean {
        val tsMs = max(ptsUs / 1000L, lastTsMs + 1L)
        val dt = if (lastTsMs < 0) 1f / 30f else ((tsMs - lastTsMs) / 1000f).coerceIn(0.001f, 0.5f)
        lastTsMs = tsMs
        dtSec = dt
        framesSeen++

        val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), tsMs)
        val faces = result.faceLandmarks()

        if (faces.isNotEmpty() && faces[0].size >= 468) {
            val lm = faces[0]
            framesDetected++
            if (needReset) { filters.forEach { it.reset() }; needReset = false }
            // Резкий скачок носа (склейка/смена сцены) — не тянем фильтр через весь кадр.
            if (havePts) {
                val jump = hypot(lm[1].x() - pts[LM.STRIDE], lm[1].y() - pts[LM.STRIDE + 1])
                if (jump > 0.20f) filters.forEach { it.reset() }
            }
            for (n in ids.indices) {
                val id = ids[n]
                val o = LM.STRIDE * id
                pts[o] = filters[3 * n].filter(lm[id].x(), dt)
                pts[o + 1] = filters[3 * n + 1].filter(lm[id].y(), dt)
                pts[o + 2] = filters[3 * n + 2].filter(lm[id].z(), dt)
            }
            havePts = true
            lostFrames = 0
            envelope = min(1f, envelope + dt / 0.15f)
        } else {
            lostFrames++
            if (lostFrames > 15) needReset = true
            envelope = max(0f, envelope - dt / 0.30f)
        }
        strength = envelope * envelope * (3f - 2f * envelope)
        return havePts && envelope > 0f
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        /**
         * Измеряет пропорции лица на референс-фото. Бросает IllegalArgumentException с понятным текстом,
         * если лицо не найдено или голова повёрнута (2D-пропорции недостоверны).
         */
        fun measureReference(context: Context, bitmap: Bitmap): FaceMetrics {
            val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .build()
            val lmk = FaceLandmarker.createFromOptions(context, opts)
            try {
                val faces = lmk.detect(BitmapImageBuilder(bitmap).build()).faceLandmarks()
                require(faces.isNotEmpty() && faces[0].size >= 468) { "На референс-фото не найдено лицо" }
                val pts = FloatArray(LM.COUNT * LM.STRIDE)
                val lm = faces[0]
                for (id in LM.USED_IDS) {
                    pts[LM.STRIDE * id] = lm[id].x()
                    pts[LM.STRIDE * id + 1] = lm[id].y()
                    pts[LM.STRIDE * id + 2] = lm[id].z()
                }
                val geo = FaceGeometry()
                require(geo.update(pts, bitmap.width, bitmap.height)) { "Не удалось построить систему координат лица" }
                val yawDeg = abs(geo.yaw) * 180f / PI.toFloat()
                require(yawDeg < 12f) { "Референс должен быть почти анфас (оценка поворота ${yawDeg.toInt()}°)" }
                val m = FaceMetrics()
                geo.measure(m)
                return m
            } finally {
                lmk.close()
            }
        }
    }
}
