package io.github.meiyongai.toki.hook

import android.app.Application
import android.content.ContentValues
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class DownloadSubtitlePathTest {
    @Test fun subtitleDocumentsAreNotRedirectedIntoVideoMediaFolders() {
        val values = ContentValues().apply { put("mime_type", "application/x-subrip") }
        val method = DownloadHook::class.java.getDeclaredMethod("redirectRelativePath", String::class.java, ContentValues::class.java)
            .apply { isAccessible = true }
        assertEquals("Download/TikTok", method.invoke(DownloadHook, "Download/TikTok", values))
    }
}
