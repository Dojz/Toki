package io.github.meiyongai.toki.download

import org.junit.Assert.*
import org.junit.Test

class CaptionTimelineTest {
    @Test fun webVttIdentifiersTagsAndCueSettingsRoundTripToSrt() {
        val cues = CaptionTimeline.parse("""
            WEBVTT

            caption-1
            00:00:01.020 --> 00:00:03.400 align:center position:50%
            <v Speaker><b>你好 &amp; world</b></v>

            caption-2
            00:00:04.000 --> 00:00:05.000
            第二句<br>Line two
        """.trimIndent())
        assertEquals(listOf(CaptionCue(1020, 3400, "你好 & world"), CaptionCue(4000, 5000, "第二句\nLine two")), cues)
        assertEquals(cues, CaptionTimeline.parse(CaptionTimeline.srt(cues)))
    }

    @Test fun bilingualRenderingUsesBothTimelinesAndClearsGaps() {
        val original = listOf(CaptionCue(1000, 2000, "Hello"), CaptionCue(3000, 5000, "Goodbye"))
        val translated = listOf(CaptionCue(1000, 2500, "你好"), CaptionCue(3200, 4900, "再见"))
        assertEquals("Hello\n你好", CaptionTimeline.textAt(listOf(original, translated), 1500))
        assertEquals("你好", CaptionTimeline.textAt(listOf(original, translated), 2000))
        assertEquals("", CaptionTimeline.textAt(listOf(original, translated), 2500))
        assertEquals("Goodbye\n再见", CaptionTimeline.textAt(listOf(original, translated), 4000))
        assertEquals("", CaptionTimeline.textAt(listOf(original, translated), 5000))
    }

    @Test fun creatorJsonAndUnicodeEntitiesRemainTimed() {
        assertEquals(listOf(CaptionCue(10, 50, "🙂")), CaptionTimeline.parse(
            """{"utterances":[{"start_time":10,"end_time":50,"text":"&#x1F642;"}]}"""))
    }

    @Test fun errorPagesAndInvalidCueDurationsAreRejected() {
        listOf("<html>Access denied</html>", "00:00:03.000 --> 00:00:01.000\ninvalid").forEach {
            assertTrue(runCatching { CaptionTimeline.parse(it) }.isFailure)
        }
    }
}
