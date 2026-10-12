package io.github.meiyongai.toki.download

import org.junit.Assert.*
import org.junit.Test

class DownloadModelsTest {
    class Post(val video: Video)
    class Video(val captionModel: Captions)
    class Captions(val captionList: List<Track>)
    class Track(val languageCode: String, val url: String, val isOriginalCaption: Boolean)

    @Test fun officialTracksKeepRolesEvenWhenSortedTranslationFirst() {
        val post = Post(Video(Captions(listOf(Track("zh-CN", "https://example.com/zh.vtt", false),
            Track("en-US", "https://example.com/en.vtt", true)))))
        val pair = checkNotNull(DownloadModels.pair(DownloadModels.captions(post), "zh-Hans"))
        assertEquals("en-US", pair.original.language)
        assertEquals("zh-CN", pair.translated.language)
    }

    @Test fun missingTranslationIsNeverSubstitutedWithOriginal() {
        val track = CaptionSource("en", listOf("https://example.com/en.vtt"), true)
        assertNull(DownloadModels.pair(listOf(track), "zh"))
    }

    @Test fun unknownMetadataHasNoPlaceholderOrEstimatedSize() {
        assertEquals("", VideoSource(listOf("https://example.com/video")).detail())
        assertEquals("720 × 1280", VideoSource(emptyList(), 720, 1280).detail())
        assertEquals("2.0 MB", VideoSource(emptyList(), bytes = 2097152).detail())
        assertEquals("720 × 1280", VideoSource(emptyList(), 720, 1280, 2097152).detail(false))
    }

    @Test fun unsafeOrNonHttpUrlsAreRejected() {
        assertTrue(DownloadModels.urls(listOf("file:///sdcard/private", "https://user:password@example.com/x", "javascript:alert(1)")).isEmpty())
    }
}
