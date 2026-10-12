package io.github.meiyongai.toki.download

import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

internal class DownloadCancelled : IOException("下载已取消")

internal class DownloadJob {
    val cancelled = AtomicBoolean(false)
    @Volatile var connection: HttpURLConnection? = null
    private val deadline = System.nanoTime() + 10 * 60 * 1000000000L
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw DownloadCancelled()
        if (System.nanoTime() > deadline) throw IOException("下载超时")
    }
    fun cancel() { cancelled.set(true); connection?.disconnect() }
}

/** No cookies or account/session data are sent to the external quality provider. */
internal object RemoteMedia {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun open(url: String, job: DownloadJob, method: String = "GET", range: String? = null): HttpURLConnection {
        job.check()
        require(DownloadModels.urls(url).isNotEmpty()) { "下载地址无效" }
        val connection = URL(url).openConnection() as HttpURLConnection
        job.connection = connection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Encoding", "identity")
        range?.let { connection.setRequestProperty("Range", it) }
        return connection
    }

    private fun read(connection: HttpURLConnection, job: DownloadJob, limit: Int): ByteArray {
        require(connection.responseCode in 200..299) { "服务器返回 ${connection.responseCode}" }
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                job.check()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "服务器返回的内容过大" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    fun text(urls: List<String>, job: DownloadJob): String {
        var last: Exception? = null
        for (url in urls) {
            val connection = open(url, job)
            try { return String(read(connection, job, 2 * 1024 * 1024), Charsets.UTF_8) }
            catch (error: Exception) { job.check(); last = error }
            finally { connection.disconnect(); job.connection = null }
        }
        throw IOException("字幕下载失败", last)
    }

    fun download(source: VideoSource, target: File, job: DownloadJob, progress: (Long, Long?) -> Unit) {
        var last: Exception? = null
        for (url in source.urls) {
            val connection = open(url, job)
            try {
                require(connection.responseCode == 200) { "视频服务器返回 ${connection.responseCode}" }
                val mime = connection.contentType.orEmpty().lowercase()
                require(!mime.contains("text") && !mime.contains("json") && !mime.contains("mpegurl")) { "地址未返回视频文件" }
                val expected = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                require(expected == null || expected <= 2L * 1024 * 1024 * 1024) { "视频超过 2 GB" }
                var copied = 0L
                connection.inputStream.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var lastProgress = 0L
                    while (true) {
                        job.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 2L * 1024 * 1024 * 1024) { "视频超过 2 GB" }
                        output.write(buffer, 0, count)
                        if (System.nanoTime() - lastProgress > 250000000) {
                            lastProgress = System.nanoTime(); progress(copied, expected)
                        }
                    }
                } }
                require(copied > 0 && (expected == null || copied == expected)) { "视频下载不完整" }
                localMetadata(target) // Reject HTML/error payloads even when the server mislabeled them.
                return
            } catch (error: Exception) {
                target.delete(); job.check(); last = error
            } finally { connection.disconnect(); job.connection = null }
        }
        throw IOException("视频下载失败", last)
    }

    fun localMetadata(file: File): VideoSource {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("文件没有视频轨道")
            return VideoSource(emptyList(), format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT), file.length())
        } finally { extractor.release() }
    }

    /** Bounded byte-range probes; an uncooperative server never triggers a full pre-download. */
    fun probe(source: VideoSource, job: DownloadJob): VideoSource {
        val connection = open(source.urls.first(), job, range = "bytes=0-262143")
        try {
            if (connection.responseCode != 206) return source
            val size = Regex("bytes \\d+-\\d+/(\\d+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
            val head = read(connection, job, 262144)
            val dimensions = Mp4Dimensions.read(head)
            if (dimensions != null) return source.copy(width = dimensions.first, height = dimensions.second, bytes = size ?: source.bytes)
            if (size == null || size <= head.size) return source.copy(bytes = size ?: source.bytes)
            connection.disconnect()
            val tailConnection = open(source.urls.first(), job, range = "bytes=${(size - 262144).coerceAtLeast(0)}-${size - 1}")
            try {
                if (tailConnection.responseCode != 206) return source.copy(bytes = size)
                val tail = Mp4Dimensions.read(read(tailConnection, job, 262144))
                return source.copy(width = tail?.first ?: source.width, height = tail?.second ?: source.height, bytes = size)
            } finally { tailConnection.disconnect() }
        } finally { connection.disconnect(); job.connection = null }
    }

    fun original(shareUrl: String, job: DownloadJob): VideoSource {
        val body = "url=${URLEncoder.encode(shareUrl, "UTF-8")}&web=1"
        val submit = open("https://www.tikwm.com/api/video/task/submit", job, "POST")
        val task = try {
            submit.doOutput = true
            submit.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            submit.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            JSONObject(String(read(submit, job, 1024 * 1024), Charsets.UTF_8)).optJSONObject("data")?.optString("task_id")
                ?.takeIf { it.isNotBlank() } ?: error("原始画质暂不可获取")
        } finally { submit.disconnect(); job.connection = null }
        repeat(30) {
            job.check()
            val connection = open("https://www.tikwm.com/api/video/task/result?task_id=${URLEncoder.encode(task, "UTF-8")}", job)
            try {
                val data = JSONObject(String(read(connection, job, 1024 * 1024), Charsets.UTF_8)).optJSONObject("data")
                when (data?.optInt("status")) {
                    2 -> {
                        val raw = data.optJSONObject("detail")?.optString("play_url").orEmpty()
                        val url = if (raw.startsWith('/')) "https://www.tikwm.com$raw" else raw
                        require(DownloadModels.urls(url).isNotEmpty()) { "原始画质地址无效" }
                        return VideoSource(listOf(url), original = true)
                    }
                    3 -> error("原始画质暂不可获取")
                }
            } finally { connection.disconnect(); job.connection = null }
            Thread.sleep(1000)
        }
        error("原始画质解析超时") // Never silently substitute a normal/transcoded video.
    }
}

/** Width/height from a complete tkhd box; never infer them from file size or URL strings. */
internal object Mp4Dimensions {
    fun read(bytes: ByteArray): Pair<Int, Int>? {
        fun uint(offset: Int): Long = (0..3).fold(0L) { value, index ->
            (value shl 8) or (bytes[offset + index].toLong() and 255)
        }
        fun boxes(start: Int, end: Int): List<Triple<Int, Int, String>> {
            val result = mutableListOf<Triple<Int, Int, String>>()
            var offset = start
            while (offset + 8 <= end) {
                val size = uint(offset)
                if (size < 8 || size > end - offset) return emptyList()
                result.add(Triple(offset, offset + size.toInt(), String(bytes, offset + 4, 4, Charsets.US_ASCII)))
                offset += size.toInt()
            }
            return if (offset == end) result else emptyList()
        }
        // Tail range starts inside mdat; only accept a complete, structurally valid moov.
        for (offset in 0 until (bytes.size - 8).coerceAtLeast(0)) {
            if (bytes[offset + 4] != 'm'.code.toByte() || bytes[offset + 5] != 'o'.code.toByte() ||
                bytes[offset + 6] != 'o'.code.toByte() || bytes[offset + 7] != 'v'.code.toByte()) continue
            val size = uint(offset)
            if (size < 8 || size > bytes.size - offset) continue
            for ((trackStart, trackEnd, type) in boxes(offset + 8, offset + size.toInt())) {
                if (type != "trak") continue
                val children = boxes(trackStart + 8, trackEnd)
                val mdia = children.firstOrNull { it.third == "mdia" } ?: continue
                val handler = boxes(mdia.first + 8, mdia.second).firstOrNull { it.third == "hdlr" } ?: continue
                if (handler.second - handler.first < 20 || String(bytes, handler.first + 16, 4, Charsets.US_ASCII) != "vide") continue
                val header = children.firstOrNull { it.third == "tkhd" } ?: continue
                val headerSize = header.second - header.first
                val version = bytes[header.first + 8].toInt()
                if (version !in 0..1 || headerSize != if (version == 0) 92 else 104) continue
                val width = (uint(header.second - 8) shr 16).toInt()
                val height = (uint(header.second - 4) shr 16).toInt()
                if (width in 1..16384 && height in 1..16384) return width to height
            }
        }
        return null
    }
}
