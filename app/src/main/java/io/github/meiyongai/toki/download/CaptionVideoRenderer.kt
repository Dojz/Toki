package io.github.meiyongai.toki.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One time-based overlay, rendered into H264; the original source is not altered. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object CaptionVideoRenderer {
    fun render(context: Context, input: File, output: File, tracks: List<List<CaptionCue>>, job: DownloadJob) {
        require(tracks.isNotEmpty() && tracks.all { it.isNotEmpty() }) { "未获取到字幕" }
        check(Looper.myLooper() != Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val active = AtomicReference<Transformer?>()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                job.check()
                val encoder = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(false)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(12000000).build())
                    .build()
                val overlay = TimedCaptionOverlay(tracks)
                val item = EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(input)))
                    .setEffects(Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))))
                    .build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoder)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished.countDown() }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            failure.set(exportException); finished.countDown()
                        }
                    }).build()
                active.set(transformer)
                transformer.start(item, output.absolutePath)
            } catch (error: Throwable) { failure.set(error); finished.countDown() }
        }
        try {
            while (!finished.await(250, TimeUnit.MILLISECONDS)) job.check()
            job.check()
            failure.get()?.let { throw java.io.IOException("字幕合成失败", it) }
            RemoteMedia.localMetadata(output)
        } finally {
            main.post { active.getAndSet(null)?.cancel() }
        }
    }
}

/** Uses the device's font fallback, wraps Chinese/Latin text and clears captions during gaps. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TimedCaptionOverlay(private val tracks: List<List<CaptionCue>>) : BitmapOverlay() {
    private val timeline = CaptionTimeline.index(tracks)
    private var videoWidth = 1080
    private var videoHeight = 1920
    private var lastText: String? = null
    private var bitmap: Bitmap? = null
    private val overlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -0.78f).setOverlayFrameAnchor(0f, -1f).build()

    override fun configure(videoSize: Size) { videoWidth = videoSize.width; videoHeight = videoSize.height; lastText = null }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val text = timeline.textAt(presentationTimeUs / 1000)
        if (text == lastText && bitmap != null) return checkNotNull(bitmap)
        lastText = text
        val previous = bitmap
        bitmap = if (text.isBlank()) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else {
            val width = (videoWidth * 0.86f).toInt().coerceAtLeast(32)
            val padding = (videoWidth * 0.014f).toInt().coerceAtLeast(4)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (videoWidth * 0.038f).coerceAtLeast(14f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setShadowLayer(2f, 0f, 1f, Color.BLACK)
            }
            fun layout() = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - padding * 2)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(2f, 1.05f).build()
            var layout = layout()
            val maxHeight = (videoHeight * 0.4f).toInt()
            while (layout.height + padding * 2 > maxHeight && paint.textSize > 12f) {
                paint.textSize = (paint.textSize * 0.85f).coerceAtLeast(12f)
                layout = layout()
            }
            require(layout.height + padding * 2 <= maxHeight) { "字幕过长，无法完整显示" }
            Bitmap.createBitmap(width, layout.height + padding * 2, Bitmap.Config.ARGB_8888).also { result ->
                val canvas = Canvas(result)
                canvas.drawRoundRect(0f, 0f, width.toFloat(), result.height.toFloat(), padding.toFloat(), padding.toFloat(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA8000000.toInt() })
                canvas.translate(padding.toFloat(), padding.toFloat())
                layout.draw(canvas)
            }
        }
        previous?.recycle()
        return checkNotNull(bitmap)
    }

    override fun release() { super.release(); bitmap?.recycle(); bitmap = null }
}
