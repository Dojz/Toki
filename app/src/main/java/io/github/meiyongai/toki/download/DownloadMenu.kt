package io.github.meiyongai.toki.download

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.toki.R
import io.github.meiyongai.toki.hook.DownloadHook
import io.github.meiyongai.toki.provider.ConfigClient
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** The existing save-video action owns this submenu; each dialog retains its own post. */
@androidx.annotation.RequiresApi(29)
internal object DownloadMenu {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(3),
        { task -> Thread(task, "TokiVideoExport").apply { isDaemon = true } })
    private val probes = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12),
        { task -> Thread(task, "TokiVideoProbe").apply { isDaemon = true } })
    private val originalWorker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(3),
        { task -> Thread(task, "TokiOriginalQuality").apply { isDaemon = true } })
    private val originalTasks = mutableMapOf<String, CompletableFuture<VideoSource>>()
    private val originalCache = object : LinkedHashMap<String, Pair<Long, VideoSource>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, VideoSource>>): Boolean = size > 16
    }

    private fun originalTask(share: String): CompletableFuture<VideoSource> {
        val future: CompletableFuture<VideoSource>
        synchronized(originalCache) {
            originalCache[share]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60 * 1000 }
                ?.let { return CompletableFuture.completedFuture(it.second) }
            originalTasks[share]?.let { return it }
            future = CompletableFuture()
            originalTasks[share] = future
        }
        try { originalWorker.execute {
            try {
                val job = DownloadJob()
                val source = RemoteMedia.original(share, job)
                val resolved = runCatching { RemoteMedia.probe(source, job) }.getOrDefault(source)
                synchronized(originalCache) { originalCache[share] = System.currentTimeMillis() to resolved }
                future.complete(resolved)
            } catch (error: Exception) { future.completeExceptionally(error) }
            finally { synchronized(originalCache) { originalTasks.remove(share) } }
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            synchronized(originalCache) { originalTasks.remove(share) }
            future.completeExceptionally(error)
        }
        return future
    }

    private fun awaitOriginal(share: String, job: DownloadJob): VideoSource {
        val future = originalTask(share)
        while (true) {
            job.check()
            try { return future.get(250, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (error: ExecutionException) { throw java.io.IOException(error.cause?.message ?: "原始画质解析失败", error.cause) }
        }
    }

    fun show(activity: Activity, ui: Context, aweme: Any): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val sources = DownloadModels.sources(aweme).toMutableList()
        if (sources.isEmpty()) return false
        val reference = WeakReference(activity)
        val share = DownloadModels.shareUrl(aweme)
        val resolutionJob = DownloadJob()
        var original = share?.let { key -> synchronized(originalCache) {
            originalCache[key]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60 * 1000 }?.second
        } }
        val adapter = Rows(ui, listOf(
            Row(ui.getString(R.string.export_normal), sources.first().detail()),
            Row(ui.getString(R.string.export_original), original?.detail().orEmpty()),
            Row(ui.getString(R.string.export_sidecars), sources.first().detail()),
            // Encoded output size cannot be known in advance, so it stays hidden.
            Row(ui.getString(R.string.export_burned), sources.first().detail(false)),
        ))
        val dialog = AlertDialog.Builder(ui).setTitle(R.string.export_title)
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> quality(activity, ui, sources) { source -> export(activity, ui, aweme, source, Mode.VIDEO) }
                    1 -> export(activity, ui, aweme, original, Mode.VIDEO, share)
                    2 -> translatedTrack(activity, ui, aweme) { translated ->
                        quality(activity, ui, sources, original, share) { source ->
                            export(activity, ui, aweme, source, Mode.SIDECARS,
                                share = if (source == null) share else null, target = translated)
                        }
                    }
                    3 -> chooseCaptionMode(activity, ui, aweme, sources, original, share)
                }
            }.setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { resolutionJob.cancel() }
        dialog.show()
        val dialogReference = WeakReference(dialog)
        val adapterReference = WeakReference(adapter)

        sources.indices.take(8).forEach { index ->
            runCatching { probes.execute {
                val resolved = runCatching { RemoteMedia.probe(sources[index], resolutionJob) }.getOrNull() ?: return@execute
                main.post {
                    if (dialogReference.get()?.isShowing != true || reference.get()?.isDestroyed != false) return@post
                    sources[index] = resolved
                    val activeAdapter = adapterReference.get() ?: return@post
                    if (index == 0) {
                        activeAdapter.rows[0].detail = resolved.detail()
                        activeAdapter.rows[2].detail = resolved.detail()
                        activeAdapter.rows[3].detail = resolved.detail(false)
                        activeAdapter.notifyDataSetChanged()
                    }
                }
            } }
        }
        // Resolve before selection to populate the inline details. Only a public share URL is used.
        if (original == null && share != null) originalTask(share).whenComplete { resolved, error ->
            if (error != null || resolved == null) return@whenComplete
            main.post {
                original = resolved
                if (dialogReference.get()?.isShowing == true && reference.get()?.isDestroyed == false) {
                    adapterReference.get()?.let { activeAdapter ->
                        activeAdapter.rows[1].detail = resolved.detail(); activeAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
        return true
    }

    private fun quality(activity: Activity, ui: Context, sources: List<VideoSource>, original: VideoSource? = null,
        share: String? = null, selected: (VideoSource?) -> Unit) {
        if (sources.size == 1 && share == null) { selected(sources.first()); return }
        val choices = sources.toList()
        val rows = choices.mapIndexed { index, source -> Row(ui.getString(R.string.export_quality, index + 1), source.detail()) }.toMutableList()
        if (share != null) rows.add(0, Row(ui.getString(R.string.export_original), original?.detail().orEmpty()))
        AlertDialog.Builder(ui).setTitle(R.string.export_choose_quality).setAdapter(Rows(ui, rows)) { _, index ->
            if (share != null && index == 0) selected(original)
            else selected(choices[index - if (share != null) 1 else 0])
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun translatedTrack(activity: Activity, ui: Context, aweme: Any, selected: (CaptionSource) -> Unit) {
        val tracks = DownloadModels.captions(aweme)
        if (tracks.none { it.original }) { missingCaptions(ui); return }
        val translated = tracks.filter { !it.original }.distinctBy { it.language }
        if (translated.isEmpty()) { missingCaptions(ui); return }
        val desired = ui.resources.configuration.locales[0].toLanguageTag()
        val ordered = translated.sortedByDescending { it.language.substringBefore('-').equals(desired.substringBefore('-'), true) }
        if (ordered.size == 1) { selected(ordered.first()); return }
        AlertDialog.Builder(ui).setTitle(R.string.export_translation_language)
            .setItems(ordered.map { languageName(it.language, ui) }.toTypedArray()) { _, index -> selected(ordered[index]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseCaptionMode(activity: Activity, ui: Context, aweme: Any, sources: List<VideoSource>,
        original: VideoSource?, share: String?) {
        AlertDialog.Builder(ui).setTitle(R.string.export_burned).setItems(arrayOf(
            ui.getString(R.string.export_original_captions), ui.getString(R.string.export_translated_captions),
            ui.getString(R.string.export_bilingual_captions))) { _, which ->
            fun proceed(target: CaptionSource?) = quality(activity, ui, sources, original, share) { source ->
                export(activity, ui, aweme, source, when (which) {
                    0 -> Mode.BURN_ORIGINAL; 1 -> Mode.BURN_TRANSLATED; else -> Mode.BURN_BILINGUAL
                }, share = if (source == null) share else null, target = target)
            }
            if (which == 0) {
                if (DownloadModels.captions(aweme).none { it.original }) missingCaptions(ui) else proceed(null)
            } else translatedTrack(activity, ui, aweme) { proceed(it) }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun missingCaptions(ui: Context) = AlertDialog.Builder(ui).setTitle(R.string.export_captions_missing)
        .setMessage(R.string.export_captions_missing_message).setPositiveButton(android.R.string.ok, null).show()

    private enum class Mode { VIDEO, SIDECARS, BURN_ORIGINAL, BURN_TRANSLATED, BURN_BILINGUAL }

    private fun export(activity: Activity, ui: Context, aweme: Any, chosen: VideoSource?, mode: Mode,
        share: String? = null, target: CaptionSource? = null) {
        val app = activity.applicationContext
        val job = DownloadJob()
        val status = TextView(ui).apply { text = ui.getString(R.string.export_preparing); setPadding(48, 32, 48, 32) }
        val dialog = AlertDialog.Builder(ui).setTitle(R.string.export_title).setView(status)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job.cancel() }.create()
        dialog.setOnCancelListener { job.cancel() }
        dialog.show()
        val reference = WeakReference(activity)
        val dialogReference = WeakReference(dialog)
        val statusReference = WeakReference(status)
        val uiReference = WeakReference(ui)
        val strings = ui.applicationContext
        fun update(text: String) { main.post { if (dialogReference.get()?.isShowing == true) statusReference.get()?.text = text } }
        val sourceTracks = DownloadModels.captions(aweme)
        val originalTrack = sourceTracks.firstOrNull { it.original }
        val originalShare = share ?: DownloadModels.shareUrl(aweme)
        val videoPath = if (ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_PATH_ENABLED))
            DownloadHook.sanitizeRelativePath(ConfigClient.getString(DownloadHook.KEY_DOWNLOAD_VIDEO_PATH,
                DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH), DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH)
            else DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH
        try { worker.execute {
            val directory = File(app.cacheDir, "toki-export/${UUID.randomUUID()}")
            try {
                check(directory.mkdirs()) { "无法创建下载缓存" }
                val source = chosen ?: awaitOriginal(checkNotNull(originalShare), job)
                if (source.original && originalShare != null) synchronized(originalCache) {
                    originalCache[originalShare] = System.currentTimeMillis() to source
                }
                val loaded = mutableListOf<Pair<CaptionSource, List<CaptionCue>>>()
                fun load(track: CaptionSource?) {
                    requireNotNull(track) { "未获取到完整官方字幕；请先在视频里开启字幕及自动翻译" }
                    loaded.add(track to CaptionTimeline.parse(RemoteMedia.text(track.urls, job)))
                }
                when (mode) {
                    Mode.VIDEO -> Unit
                    Mode.SIDECARS, Mode.BURN_BILINGUAL -> { load(originalTrack); load(target) }
                    Mode.BURN_ORIGINAL -> load(originalTrack)
                    Mode.BURN_TRANSLATED -> load(target)
                }
                val input = File(directory, "source.mp4")
                update(strings.getString(R.string.export_downloading) + source.detail().let { if (it.isBlank()) "" else "\n$it" })
                RemoteMedia.download(source, input, job) { done, total ->
                    val progress = if (total != null) "${done * 100 / total}%" else String.format(Locale.ROOT, "%.1f MB", done / 1048576.0)
                    update("${strings.getString(R.string.export_downloading)} $progress\n${source.detail()}")
                }
                val stamp = System.currentTimeMillis()
                val id = originalShare?.substringAfterLast('/') ?: "video"
                val stem = "TikTok_${id}_$stamp"
                val files = mutableListOf<PublishedFile>()
                if (mode in setOf(Mode.BURN_ORIGINAL, Mode.BURN_TRANSLATED, Mode.BURN_BILINGUAL)) {
                    update(strings.getString(R.string.export_rendering))
                    val output = File(directory, "captioned.mp4")
                    CaptionVideoRenderer.render(app, input, output, loaded.map { it.second }, job)
                    files.add(PublishedFile(output, "$stem.captioned.mp4", "video/mp4", true))
                } else {
                    files.add(PublishedFile(input, "$stem.mp4", "video/mp4", true))
                    if (mode == Mode.SIDECARS) loaded.forEach { (track, cues) ->
                        val role = if (track.original) "original" else "translated"
                        val language = track.language.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
                        val name = "$stem.$role.$language.srt"
                        val file = File(directory, name).apply { writeText(CaptionTimeline.srt(cues), Charsets.UTF_8) }
                        files.add(PublishedFile(file, name, "application/x-subrip", false))
                    }
                }
                MediaPublisher.publish(app, files, videoPath, job)
                main.post {
                    dialogReference.get()?.takeIf { it.isShowing }?.dismiss()
                    Toast.makeText(app, strings.getString(if (mode == Mode.SIDECARS) R.string.export_saved_sidecars else R.string.export_saved), Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                main.post {
                    dialogReference.get()?.takeIf { it.isShowing }?.dismiss()
                    if (error !is DownloadCancelled && reference.get()?.isDestroyed == false) uiReference.get()?.let { activeUi ->
                        AlertDialog.Builder(activeUi).setTitle(R.string.export_failed).setMessage(error.message.orEmpty())
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            } finally {
                // Only this job's explicitly-created cache directory is removed.
                directory.listFiles()?.forEach { it.delete() }; directory.delete()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            dialog.dismiss(); Toast.makeText(app, ui.getString(R.string.export_busy), Toast.LENGTH_LONG).show()
        }
    }

    private fun languageName(code: String, ui: Context): String = Locale.forLanguageTag(code)
        .getDisplayName(ui.resources.configuration.locales[0]).takeIf { it.isNotBlank() } ?: code

    internal data class Row(val title: String, var detail: String)
    private class Rows(private val context: Context, items: List<Row>) : BaseAdapter() {
        val rows = items.toMutableList()
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = context.resources.displayMetrics.density
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * density).toInt(), (18 * density).toInt(), (24 * density).toInt(), (18 * density).toInt())
            }
            row.addView(TextView(context).apply { text = rows[position].title; textSize = 16f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (rows[position].detail.isNotBlank()) row.addView(TextView(context).apply {
                text = rows[position].detail; textSize = 12f; setTextColor(Color.GRAY)
                gravity = Gravity.END; setPadding((12 * density).toInt(), 0, 0, 0)
            })
            return row
        }
    }
}
