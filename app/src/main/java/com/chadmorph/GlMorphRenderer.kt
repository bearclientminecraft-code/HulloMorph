package com.chadmorph

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GPU-часть: декодер -> SurfaceTexture (OES) -> шейдер (поворот + варп + тени) -> Surface энкодера.
 * Второй проход рендерит уменьшенный кадр без морфинга в FBO для MediaPipe (readback в Bitmap).
 *
 * Все методы вызывать из ОДНОГО потока (того, где создан объект): на нём висит EGL-контекст.
 */
class GlMorphRenderer(
    encoderSurface: Surface,
    private val outW: Int,
    private val outH: Int,
    rotationDeg: Int,
    analysisMaxSide: Int = 640
) {
    val decoderSurface: Surface
    val analysisWidth: Int
    val analysisHeight: Int

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0

    private val surfaceTexture: SurfaceTexture
    private val frameThread = HandlerThread("morph-frames")
    private val frameSem = Semaphore(0)

    private val stMat = FloatArray(16)
    private val rotMat = FloatArray(16)
    private val texMat = FloatArray(16)

    private val readBuf: ByteBuffer
    private val analysisBitmap: Bitmap
    private val vertexBuf: FloatBuffer

    private var aPos = 0; private var aUv = 0
    private var uTex = 0; private var uTexMat = 0; private var uFlip = 0

    private val meshVertexBuf: FloatBuffer
    private val meshIndexBuf: ShortBuffer
    private var meshIndexCount = 0

    init {
        val scale = analysisMaxSide.toFloat() / max(outW, outH)
        analysisWidth = max(16, (outW * scale).roundToInt().coerceAtMost(outW))
        analysisHeight = max(16, (outH * scale).roundToInt().coerceAtMost(outH))

        setupEgl(encoderSurface)

        program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
        uFlip = GLES20.glGetUniformLocation(program, "uFlip")

        // Буферы сетки морфинга (выделяются один раз).
        val probe = DeformationMesh()
        meshVertexBuf = ByteBuffer.allocateDirect(probe.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        meshIndexBuf = ByteBuffer.allocateDirect(probe.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        meshIndexBuf.put(probe.indices); meshIndexBuf.position(0)
        meshIndexCount = probe.indices.size

        // Полноэкранный quad: x, y, u, v (v вверх).
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        vertexBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().also { it.put(quad); it.position(0) }

        // Внешняя текстура под декодер.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        frameThread.start()
        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setOnFrameAvailableListener({ frameSem.release() }, Handler(frameThread.looper))
        decoderSurface = Surface(surfaceTexture)

        // R: апрайт-UV (y вверх) -> координаты кодированного кадра. Поворот на +rotation вокруг центра.
        Matrix.setIdentityM(rotMat, 0)
        Matrix.translateM(rotMat, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMat, 0, rotationDeg.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotMat, 0, -0.5f, -0.5f, 0f)
        Matrix.setIdentityM(texMat, 0)

        // FBO для анализа.
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, analysisWidth, analysisHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $status" }

        readBuf = ByteBuffer.allocateDirect(analysisWidth * analysisHeight * 4).order(ByteOrder.nativeOrder())
        analysisBitmap = Bitmap.createBitmap(analysisWidth, analysisHeight, Bitmap.Config.ARGB_8888)
    }

    private fun setupEgl(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    /** Ждёт кадр от декодера (после releaseOutputBuffer(render=true)) и фиксирует его в текстуре. */
    fun awaitFrame(timeoutMs: Long = 5000) {
        if (!frameSem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Таймаут ожидания кадра от декодера")
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMat)
        Matrix.multiplyMM(texMat, 0, stMat, 0, rotMat, 0)
    }

    /**
     * Рендер уменьшенного кадра в Bitmap для MediaPipe (строки сверху вниз).
     * mesh == null -> исходный кадр без морфинга; иначе -> с морфингом (для debug-визуализации).
     */
    fun renderAnalysis(mesh: DeformationMesh?): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, analysisWidth, analysisHeight)
        drawScene(mesh, true)
        readBuf.clear()
        GLES20.glReadPixels(0, 0, analysisWidth, analysisHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        readBuf.rewind()
        analysisBitmap.copyPixelsFromBuffer(readBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return analysisBitmap
    }

    /** Финальный рендер в Surface энкодера. ptsUs сохраняется как timestamp кадра. */
    fun renderFinal(mesh: DeformationMesh?, ptsUs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        drawScene(mesh, false)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawScene(mesh: DeformationMesh?, flipY: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        GLES20.glUniform1f(uFlip, if (flipY) -1f else 1f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)

        // 1) весь кадр без деформации
        vertexBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
        vertexBuf.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2) сетка деформации поверх (вне ROI смещение = 0, шва нет)
        if (mesh != null && mesh.active) {
            meshVertexBuf.clear()
            meshVertexBuf.put(mesh.vertices, 0, mesh.vertexCount * 4)
            meshVertexBuf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, meshVertexBuf)
            meshVertexBuf.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, meshVertexBuf)
            meshIndexBuf.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, meshIndexCount, GLES20.GL_UNSIGNED_SHORT, meshIndexBuf)
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    fun release() {
        runCatching { decoderSurface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { frameThread.quitSafely() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(2, intArrayOf(oesTex, fboTex), 0)
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        analysisBitmap.recycle()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        private const val VERTEX_SRC = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform mat4 uTexMat;
uniform float uFlip;
varying vec2 vTc;
void main() {
    gl_Position = vec4(aPos.x, aPos.y * uFlip, 0.0, 1.0);
    vTc = (uTexMat * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT_SRC = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTc;
uniform samplerExternalOES uTex;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vTc).rgb, 1.0);
}
"""
    }
}
