package io.github.meiyongai.toki.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class MediaPublisherTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var provider: RecordingProvider

    @Before fun setup() {
        provider = RecordingProvider().apply { root = directory.newFolder("media") }
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    private fun files(): List<PublishedFile> = listOf(
        PublishedFile(directory.newFile("video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }, "video.mp4", "video/mp4", true),
        PublishedFile(directory.newFile("original.srt").apply { writeText("original") }, "video.original.en.srt", "application/x-subrip", false),
        PublishedFile(directory.newFile("translated.srt").apply { writeText("译文") }, "video.translated.zh.srt", "application/x-subrip", false),
    )

    @Test fun videoAndBothSubtitlesBecomeVisibleOnlyAfterAllThreeWrites() {
        val uris = MediaPublisher.publish(RuntimeEnvironment.getApplication(), files(), "Movies/Custom", DownloadJob())
        assertEquals(3, uris.size)
        assertEquals(listOf("Movies/Custom", "Download/TikTok", "Download/TikTok"), provider.insertions.map {
            it.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
        })
        assertTrue(provider.insertions.all { it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1 })
        assertEquals(listOf(3, 3, 3), provider.completedFilesAtCommit)
        assertEquals(3, provider.records.size)
    }

    @Test fun failingSecondFileRollsBackPendingVideoAndSubtitleRows() {
        provider.failWrite = 2
        assertTrue(runCatching { MediaPublisher.publish(RuntimeEnvironment.getApplication(), files(), "Movies/TikTok", DownloadJob()) }.isFailure)
        assertEquals(2, provider.deletions)
        assertTrue(provider.records.isEmpty())
        assertTrue(provider.completedFilesAtCommit.isEmpty())
    }

    @Test fun cancelledJobNeverPublishesRows() {
        val job = DownloadJob().apply { cancel() }
        assertTrue(runCatching { MediaPublisher.publish(RuntimeEnvironment.getApplication(), files(), "Movies/TikTok", job) }
            .exceptionOrNull() is DownloadCancelled)
        assertTrue(provider.insertions.isEmpty())
    }

    class RecordingProvider : ContentProvider() {
        lateinit var root: File
        val records = linkedMapOf<Uri, File>()
        val insertions = mutableListOf<ContentValues>()
        val completedFilesAtCommit = mutableListOf<Int>()
        var failWrite = 0
        var deletions = 0
        private var opens = 0
        override fun onCreate(): Boolean = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val result = uri.buildUpon().appendPath((insertions.size + 1).toString()).build()
            insertions.add(ContentValues(checkNotNull(values)))
            records[result] = File(root, "${insertions.size}.data")
            return result
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            if (opens == failWrite) throw FileNotFoundException("injected storage failure")
            return ParcelFileDescriptor.open(checkNotNull(records[uri]), ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            completedFilesAtCommit.add(records.values.count { it.length() > 0 })
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            records.remove(uri)?.delete(); deletions++; return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String = "application/octet-stream"
    }
}
