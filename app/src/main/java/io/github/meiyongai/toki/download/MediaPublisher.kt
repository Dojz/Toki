package io.github.meiyongai.toki.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

internal data class PublishedFile(val file: File, val name: String, val mime: String, val video: Boolean)

/** Publish only after all inputs/outputs are complete; delete owned rows after any failure. */
@androidx.annotation.RequiresApi(29)
internal object MediaPublisher {
    fun publish(context: Context, files: List<PublishedFile>, videoPath: String, job: DownloadJob): List<Uri> {
        val resolver = context.contentResolver
        val rows = mutableListOf<Uri>()
        try {
            files.forEach { item ->
                job.check()
                val collection = if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (item.video) videoPath else "${Environment.DIRECTORY_DOWNLOADS}/TikTok")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(collection, values)) { "无法创建下载文件" }
                rows.add(uri)
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    item.file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            job.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            job.check()
            rows.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            }
            return rows.toList()
        } catch (error: Exception) {
            rows.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw error
        }
    }
}
