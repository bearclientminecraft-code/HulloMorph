package com.chadmorph

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChadMorphScreen() }
    }
}

@Composable
private fun ChadMorphScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Выбери видео") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }

    var config by remember { mutableStateOf(MorphConfig.STRONG) }
    var linked by remember { mutableStateOf(true) }
    var reference by remember { mutableStateOf<ReferenceProfile?>(null) }
    var referenceNote by remember { mutableStateOf("Референс не задан (абсолютные направления)") }

    // Пока идёт обработка, экран не гасим (обработка живёт в Activity, не в сервисе).
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selectedUri = uri
            resultUri = null
            status = "Видео выбрано."
        }
    }

    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            referenceNote = "Анализ референса…"
            job = scope.launch {
                try {
                    val m = withContext(Dispatchers.IO) {
                        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            d.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 1600))
                        }
                        try { FaceTracker.measureReference(context.applicationContext, bmp) } finally { bmp.recycle() }
                    }
                    reference = ReferenceProfile(m, mirror = true)
                    referenceNote = "Референс загружен: пропорции челюсти/щёк/глаз/подбородка берутся из фото"
                } catch (e: CancellationException) {
                    referenceNote = "Отменено"
                } catch (t: Throwable) {
                    referenceNote = "Референс не принят: ${t.message ?: t.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun runExport(preview: Boolean, debug: Boolean = false) {
        val uri = selectedUri ?: return
        busy = true
        progress = 0f
        resultUri = null
        status = if (debug) "Debug-кадр…" else if (preview) "Предпросмотр (первые 3 сек)…" else "Экспорт…"
        val cfg = config
        val ref = reference
        job = scope.launch {
            val tmp = File(context.cacheDir, "morph_${System.currentTimeMillis()}.mp4")
            try {
                val res = withContext(Dispatchers.IO) {
                    VideoMorphProcessor(context.applicationContext).process(
                        uri, tmp, cfg, ref, debug,
                        if (debug) 1_500_000L else if (preview) 3_000_000L else 0L,
                        { progress = it },
                        { !isActive }
                    )
                }
                if (res.cancelled) {
                    status = "Отменено"
                } else if (debug) {
                    val bmp = res.debugBitmap
                    status = if (bmp == null) "Debug: морфинг не активировался (лицо не найдено или эффект выключен)"
                    else {
                        val name = "chadmorph_debug_" + System.currentTimeMillis() + ".png"
                        withContext(Dispatchers.IO) { saveDebugPng(context, bmp, name) }
                        bmp.recycle()
                        "Debug сохранён: Pictures/ChadMorph/$name\nСлева: поле деформации (красная сетка = откуда берутся пиксели), " +
                            "справа: результат + регулярная сетка. min Λ=${"%.2f".format(res.minLambda)}"
                    }
                } else {
                    val name = (if (preview) "chadmorph_preview_" else "chadmorph_") +
                        System.currentTimeMillis() + ".mp4"
                    val saved = withContext(Dispatchers.IO) { saveToGallery(context, tmp, name) }
                    resultUri = saved
                    status = "Готово: Movies/ChadMorph/$name\n" +
                        "${res.outW}x${res.outH}, rotation=${res.rotation}, " +
                        "кадров ${res.framesProcessed}, лицо найдено в ${res.framesFaceDetected}, " +
                        "морф применён в ${res.framesMorphed}, ограничитель складок сработал в ${res.framesLimited} " +
                        "(min Λ=${"%.2f".format(res.minLambda)})\n${res.audioNote}\n" +
                        "время: ${res.elapsedMs / 1000} с"
                }
            } catch (e: CancellationException) {
                status = "Отменено"
            } catch (t: Throwable) {
                Log.e("ChadMorph", "export failed", t)
                status = "Ошибка: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                tmp.delete()
                busy = false
            }
        }
    }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("CHAD MORPH", style = MaterialTheme.typography.headlineMedium)
                Text("v0.3 • офлайн • MediaCodec + GPU", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text(if (selectedUri == null) "Выбрать видео" else "Выбрать другое видео") }

                Text(selectedUri?.lastPathSegment ?: "Файл не выбран")
                Text("Пресет", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { config = MorphConfig.OFF }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Выкл") }
                    OutlinedButton(onClick = { config = MorphConfig.STRONG }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Strong") }
                    OutlinedButton(onClick = { config = MorphConfig.EXTREME_HUNTER }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Extreme") }
                }
                MorphSlider("Общая сила", config.strength, !busy) { config = config.copy(strength = it) }
                MorphSlider("Chin projection", config.chinProjection, !busy) { config = config.copy(chinProjection = it) }
                MorphSlider("Chin width", config.chinWidth, !busy) { config = config.copy(chinWidth = it) }
                MorphSlider("Симметрия", config.symmetry, !busy) { config = config.copy(symmetry = it) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Лево/право связаны")
                    Switch(checked = linked, onCheckedChange = { linked = it; if (it) config = config.copy(right = config.left) }, enabled = !busy)
                }
                if (linked) {
                    SideSliders("Лицо (обе стороны)", config.left, !busy) { config = config.copy(left = it, right = it) }
                } else {
                    SideSliders("Левая сторона кадра", config.left, !busy) { config = config.copy(left = it) }
                    SideSliders("Правая сторона кадра", config.right, !busy) { config = config.copy(right = it) }
                }

                Text("Референс пропорций", style = MaterialTheme.typography.titleMedium)
                Text(referenceNote, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refPicker.launch(arrayOf("image/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Фото-референс") }
                    OutlinedButton(
                        onClick = { reference = null; referenceNote = "Референс не задан (абсолютные направления)" },
                        enabled = !busy && reference != null, modifier = Modifier.weight(1f)
                    ) { Text("Сбросить") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { runExport(true) },
                        enabled = selectedUri != null && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Предпросмотр 3 с") }
                    Button(
                        onClick = { runExport(false) },
                        enabled = selectedUri != null && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Экспорт MP4") }
                }
                OutlinedButton(
                    onClick = { runExport(preview = true, debug = true) },
                    enabled = selectedUri != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Debug: сетка до/после (PNG)") }
                OutlinedButton(
                    onClick = {
                        val uri = selectedUri ?: return@OutlinedButton
                        busy = true
                        status = "Анализ кадров…"
                        job = scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { analyzeVideo(context, uri) }
                                status = "Анализ: ${r.framesChecked} кадров, ${r.framesWithFace} с лицом, " +
                                    "до ${r.maxLandmarks} landmarks."
                            } catch (e: CancellationException) {
                                status = "Отменено"
                            } catch (t: Throwable) {
                                status = "Ошибка: ${t.message ?: t.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = selectedUri != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Только проверить лицо в видео") }

                if (busy) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Отмена")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                resultUri?.let { uri ->
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Открыть результат") }
                }
                HorizontalDivider()
                Text(
                    "Не сворачивай приложение во время экспорта: обработка выполняется внутри Activity.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SideSliders(title: String, p: SideParams, enabled: Boolean, onChange: (SideParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        MorphSlider("Jaw width", p.jaw, enabled) { onChange(p.copy(jaw = it)) }
        MorphSlider("Gonial angle", p.gonial, enabled) { onChange(p.copy(gonial = it)) }
        MorphSlider("Hollow cheeks", p.hollow, enabled) { onChange(p.copy(hollow = it)) }
        MorphSlider("Cheekbone", p.bone, enabled) { onChange(p.copy(bone = it)) }
        MorphSlider("Canthal tilt", p.tilt, enabled) { onChange(p.copy(tilt = it)) }
        MorphSlider("Upper lid drop", p.lidDrop, enabled) { onChange(p.copy(lidDrop = it)) }
        MorphSlider("Lower lid raise", p.lowerLid, enabled) { onChange(p.copy(lowerLid = it)) }
    }
}

private fun saveDebugPng(context: Context, bmp: Bitmap, displayName: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChadMorph")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Не удалось создать запись в MediaStore")
    context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return uri
}

@Composable
private fun MorphSlider(label: String, value: Float, enabled: Boolean, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${(value * 100).toInt()}%")
        }
        Slider(value = value, onValueChange = onValue, valueRange = 0f..1f, enabled = enabled)
    }
}

/** Копирует готовый MP4 в Movies/ChadMorph через MediaStore (разрешения не нужны, Android 10+). */
private fun saveToGallery(context: Context, file: File, displayName: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ChadMorph")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Не удалось создать запись в MediaStore")
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

private data class AnalysisSummary(
    val framesChecked: Int,
    val framesWithFace: Int,
    val maxLandmarks: Int
)

/** Быстрая диагностика (2 fps через MediaMetadataRetriever). Основной экспорт её не использует. */
private fun analyzeVideo(context: Context, uri: Uri): AnalysisSummary {
    val modelOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(modelOptions)
        .setRunningMode(RunningMode.VIDEO)
        .setNumFaces(1)
        .setOutputFaceBlendshapes(false)
        .build()

    val landmarker = FaceLandmarker.createFromOptions(context, options)
    val retriever = MediaMetadataRetriever()
    var checked = 0
    var withFace = 0
    var maxLandmarks = 0

    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        require(durationMs > 0L) { "Не удалось прочитать длительность видео" }

        var timestamp = 0L
        while (timestamp < durationMs) {
            val frame: Bitmap? = retriever.getFrameAtTime(timestamp * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), timestamp)
                checked++
                val faces = result.faceLandmarks()
                if (faces.isNotEmpty()) {
                    withFace++
                    maxLandmarks = max(maxLandmarks, faces.first().size)
                }
                frame.recycle()
            }
            timestamp += 500L
        }
    } finally {
        retriever.release()
        landmarker.close()
    }
    return AnalysisSummary(checked, withFace, maxLandmarks)
}
