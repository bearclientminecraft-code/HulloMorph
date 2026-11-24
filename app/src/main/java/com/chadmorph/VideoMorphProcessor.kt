package com.chadmorph

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
    for (i in 0 until ex.trackCount) {
        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix)) return i
    }
    return -1
}

/** Копирует AAC-дорожку «как есть» (без перекодирования), сохраняя исходные timestamp. */
private class AudioCopier private constructor(
    private val extractor: MediaExtractor,
    val format: MediaFormat,
    val mime: String
) {
    val supported: Boolean get() = mime == MediaFormat.MIMETYPE_AUDIO_AAC
    var track = -1
    var samples = 0; private set
    private var done = false
    private val info = MediaCodec.BufferInfo()
    private val buffer: ByteBuffer = ByteBuffer.allocate(
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 65536) else 512 * 1024
    )

    /** Пишет все аудио-сэмплы с timestamp <= limitUs (и <= endUs). */
    fun pump(muxer: MediaMuxer, limitUs: Long, endUs: Long) {
        if (done || track < 0) return
        while (true) {
            val t = extractor.sampleTime
            if (t < 0 || t > endUs) { done = true; return }
            if (t > limitUs) return
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) { done = true; return }
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, t, flags)
            muxer.writeSampleData(track, buffer, info)
            samples++
            extractor.advance()
        }
    }

    fun release() = runCatching { extractor.release() }

    companion object {
        fun open(context: Context, uri: Uri): AudioCopier? {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
            } catch (e: Exception) {
                ex.release(); return null
            }
            val idx = selectTrack(ex, "audio/")
            if (idx < 0) { ex.release(); return null }
            ex.selectTrack(idx)
            val f = ex.getTrackFormat(idx)
            return AudioCopier(ex, f, f.getString(MediaFormat.KEY_MIME) ?: "")
        }
    }
}

class VideoMorphProcessor(private val context: Context) {

    data class Result(
        val cancelled: Boolean,
        val outW: Int,
        val outH: Int,
        val rotation: Int,
        val framesProcessed: Int,
        val framesFaceDetected: Int,
        val framesMorphed: Int,
        val framesLimited: Int,
        val minLambda: Float,
        val debugBitmap: Bitmap?,
        val audioNote: String,
        val elapsedMs: Long
    )

    /**
     * Полный офлайн-транскод: MediaExtractor -> MediaCodec(decoder) -> GL (морфинг) ->
     * MediaCodec(encoder, H.264) -> MediaMuxer(MP4). Кадры обрабатываются по одному.
     *
     * @param maxDurationUs 0 = всё видео, иначе — только первые N мкс (предпросмотр)
     */
    fun process(
        input: Uri,
        output: File,
        config: MorphConfig,
        reference: ReferenceProfile?,
        debugCapture: Boolean,
        maxDurationUs: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result {
        val startNs = System.nanoTime()
        var videoEx: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var renderer: GlMorphRenderer? = null
        var tracker: FaceTracker? = null
        var audio: AudioCopier? = null
        var muxerStarted = false
        var cancelled = false
        var framesProcessed = 0
        var framesMorphed = 0
        var framesLimited = 0
        var minLambda = 1f
        var debugBitmap: Bitmap? = null
        var audioNote = "аудио: нет дорожки"
        var outW = 0
        var outH = 0
        var rotation = 0

        try {
            // ---------- Вход ----------
            val ex = MediaExtractor().also { videoEx = it }
            ex.setDataSource(context, input, null)
            val vTrack = selectTrack(ex, "video/")
            require(vTrack >= 0) { "В файле нет видеодорожки" }
            ex.selectTrack(vTrack)
            val inFormat = ex.getTrackFormat(vTrack)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val codedW = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val codedH = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            rotation = readRotation(inFormat, input)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
                inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            // Апрайт-размер (после учёта rotation) и ограничение 1920x1080 (по длинной/короткой стороне).
            val upW = if (rotation % 180 == 90) codedH else codedW
            val upH = if (rotation % 180 == 90) codedW else codedH
            val scale = min(1f, min(1920f / max(upW, upH), 1080f / min(upW, upH)))
            outW = max(2, (upW * scale).toInt() / 2 * 2)
            outH = max(2, (upH * scale).toInt() / 2 * 2)

            val fps = readFps(inFormat).coerceIn(15, 60)
            val inBitrate = if (inFormat.containsKey(MediaFormat.KEY_BIT_RATE))
                inFormat.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            val bitrate = max((outW.toLong() * outH * fps * 0.15).toInt(), inBitrate)
                .coerceIn(4_000_000, 16_000_000)

            // ---------- Энкодер (Surface input) ----------
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encInput = enc.createInputSurface()
            enc.start()

            // ---------- GL + трекер ----------
            val rend = GlMorphRenderer(encInput, outW, outH, rotation).also { renderer = it }
            val trk = FaceTracker(context).also { tracker = it }
            val field = MorphField()
            val emptyField = MorphField()
            val engine = FaceMorphEngine()
            val mesh = DeformationMesh(minRatio = config.minAreaRatio, maxRatio = config.maxAreaRatio)

            // ---------- Декодер ----------
            // Поворот делаем сами в шейдере -> декодеру rotation обнуляем, чтобы не повернуть дважды.
            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val dec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            dec.configure(inFormat, rend.decoderSurface, null, 0)
            dec.start()

            // ---------- Muxer + аудио ----------
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .also { muxer = it }
            mux.setOrientationHint(0) // кадры уже апрайт
            audio = AudioCopier.open(context, input)
            audio?.let { a ->
                if (a.supported) {
                    a.track = mux.addTrack(a.format)
                    audioNote = "аудио: скопировано без перекодирования (AAC)"
                } else {
                    audioNote = "аудио: формат ${a.mime} не поддержан в MP4 без перекодирования — пропущено"
                }
            }

            // ---------- Главный цикл ----------
            val firstTs = max(0L, ex.sampleTime)
            val endTs = if (maxDurationUs > 0) firstTs + maxDurationUs else Long.MAX_VALUE
            val spanUs = if (maxDurationUs > 0) maxDurationUs else max(1L, durationUs - firstTs)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var videoTrack = -1

            fun drainEncoder(timeoutUs: Long) {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, timeoutUs)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Формат энкодера изменился повторно" }
                        videoTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx < 0) continue
                    val buf = enc.getOutputBuffer(idx)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                    if (encInfo.size > 0 && muxerStarted) {
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        mux.writeSampleData(videoTrack, buf, encInfo)
                        audio?.pump(mux, encInfo.presentationTimeUs, endTs)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                        return
                    }
                }
            }

            while (!encoderDone) {
                if (isCancelled()) { cancelled = true; break }

                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(0)
                    if (idx >= 0) {
                        val ib = dec.getInputBuffer(idx)!!
                        val ts = ex.sampleTime
                        val size = if (ts < 0 || ts > endTs) -1 else ex.readSampleData(ib, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, ts, 0)
                            ex.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val out = dec.dequeueOutputBuffer(decInfo, 10_000)
                    if (out >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = decInfo.size > 0
                        dec.releaseOutputBuffer(out, render)
                        if (render) {
                            val pts = decInfo.presentationTimeUs
                            rend.awaitFrame()
                            val bmp = rend.renderAnalysis(null)
                            var active = false
                            if (trk.update(bmp, pts)) {
                                val built = engine.build(
                                    trk.pts, outW, outH, config, trk.strength, trk.dtSec, reference, field
                                )
                                if (built) active = mesh.update(field, outW, outH)
                                else mesh.update(emptyField, outW, outH) // сброс состояния лимитера
                            } else {
                                engine.reset()
                                mesh.update(emptyField, outW, outH)
                            }
                            if (active && debugBitmap == null && debugCapture && framesProcessed >= 8) {
                                val before = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                val morphed = rend.renderAnalysis(mesh).copy(Bitmap.Config.ARGB_8888, false)
                                debugBitmap = MeshDebugOverlay.compose(before, morphed, mesh, field, outW, outH)
                                before.recycle(); morphed.recycle()
                            }
                            rend.renderFinal(if (active) mesh else null, pts)
                            framesProcessed++
                            if (active) {
                                framesMorphed++
                                minLambda = min(minLambda, mesh.minLambda)
                                if (mesh.minLambda < 0.98f) framesLimited++
                            }
                            if (framesProcessed % 4 == 0) {
                                onProgress(((pts - firstTs).toFloat() / spanUs).coerceIn(0f, 1f))
                            }
                        }
                        if (eos) {
                            decoderDone = true
                            enc.signalEndOfInputStream()
                        }
                    }
                }

                drainEncoder(if (decoderDone) 10_000 else 0)
            }

            if (!cancelled) {
                audio?.pump(mux, Long.MAX_VALUE, endTs)
                onProgress(1f)
                if (audio?.supported == true && audio?.samples == 0) audioNote = "аудио: дорожка пуста"
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { renderer?.release() }
            runCatching { tracker?.close() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { audio?.release() }
            runCatching { videoEx?.release() }
        }

        val trkDetected = tracker?.framesDetected ?: 0
        return Result(
            cancelled, outW, outH, rotation, framesProcessed, trkDetected, framesMorphed,
            framesLimited, minLambda, debugBitmap, audioNote, (System.nanoTime() - startNs) / 1_000_000
        )
    }

    private fun readRotation(format: MediaFormat, uri: Uri): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            return ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            ((v % 360) + 360) % 360
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }

    private fun readFps(f: MediaFormat): Int {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: Exception) {
            try { f.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() } catch (e2: Exception) { 30 }
        }
    }
}
