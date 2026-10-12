package io.github.meiyongai.toki.download

import org.json.JSONObject
import java.util.Locale

internal data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/** Complete timed text, shared by SRT export and the video renderer. */
internal object CaptionTimeline {
    private val timing = Regex("^((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s+-->\\s+((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})(?:\\s.*)?$")

    fun parse(raw: String): List<CaptionCue> {
        require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "字幕文件过大" }
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        val cues = if (text.startsWith("{")) {
            val root = JSONObject(text)
            val entries = root.optJSONArray("utterances") ?: root.optJSONArray("captions")
                ?: error("不支持的字幕内容")
            (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val start = entry.optLong("start_time", -1)
                val end = entry.optLong("end_time", -1)
                valid(start, end, clean(entry.optString("text")))
            }
        } else {
            val lines = text.lines()
            buildList {
                var index = 0
                while (index < lines.size) {
                    if (lines[index].trim().let { it == "STYLE" || it == "REGION" || it.startsWith("NOTE") }) {
                        while (index < lines.size && lines[index].isNotBlank()) index++
                        continue
                    }
                    val match = timing.matchEntire(lines[index].trim())
                    if (match == null) { index++; continue }
                    val start = timestamp(match.groupValues[1])
                    val end = timestamp(match.groupValues[2])
                    val body = mutableListOf<String>()
                    index++
                    while (index < lines.size && lines[index].isNotBlank() &&
                        !timing.matches(lines[index].trim())) body.add(lines[index++])
                    valid(start, end, clean(body.joinToString("\n")))?.let(::add)
                }
            }
        }
        require(cues.isNotEmpty()) { "未获取到完整字幕时间轴" }
        require(cues.size <= 20000 && cues.all { it.text.length <= 8192 }) { "字幕内容过大" }
        return cues.distinct().sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    private fun valid(start: Long, end: Long, text: String): CaptionCue? =
        if (start >= 0 && end > start && end <= 24 * 60 * 60 * 1000L && text.isNotBlank())
            CaptionCue(start, end, text) else null

    private fun timestamp(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().split('.')
        val minutes = parts[parts.lastIndex - 1].toLong()
        require(minutes < 60 && seconds[0].toLong() < 60) { "字幕时间格式不正确" }
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        return ((hours * 60 + minutes) * 60 + seconds[0].toLong()) * 1000 + seconds[1].toLong()
    }

    private fun clean(value: String): String {
        var text = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
        text = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { match ->
            val digits = match.groupValues[1]
            val code = if (digits.startsWith('x')) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                String(Character.toChars(code)) else match.value
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&").trim()
    }

    fun srt(cues: List<CaptionCue>): String = cues.mapIndexed { index, cue ->
        "${index + 1}\n${format(cue.startMs)} --> ${format(cue.endMs)}\n${cue.text}\n"
    }.joinToString("\n")

    private fun format(time: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
        time / 3600000, time / 60000 % 60, time / 1000 % 60, time % 1000)

    fun textAt(tracks: List<List<CaptionCue>>, timeMs: Long): String = index(tracks).textAt(timeMs)

    fun index(tracks: List<List<CaptionCue>>): CaptionIndex {
        val changes = java.util.TreeMap<Long, MutableList<Triple<Int, Int, Boolean>>>()
        tracks.forEachIndexed { track, cues -> cues.forEachIndexed { cue, value ->
            changes.getOrPut(value.startMs) { mutableListOf() }.add(Triple(track, cue, true))
            changes.getOrPut(value.endMs) { mutableListOf() }.add(Triple(track, cue, false))
        } }
        val active = tracks.map { sortedSetOf<Int>() }
        val states = changes.map { (time, events) ->
            events.forEach { (track, cue, adding) -> if (adding) active[track].add(cue) else active[track].remove(cue) }
            time to active.mapIndexed { track, indices -> indices.joinToString("\n") { tracks[track][it].text } }
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
        }
        return CaptionIndex(states)
    }
}

internal class CaptionIndex(private val states: List<Pair<Long, String>>) {
    fun textAt(timeMs: Long): String {
        var start = 0
        var end = states.size
        while (start < end) {
            val middle = (start + end) ushr 1
            if (states[middle].first <= timeMs) start = middle + 1 else end = middle
        }
        return if (start == 0) "" else states[start - 1].second
    }
}
