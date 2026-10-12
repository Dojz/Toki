package io.github.meiyongai.toki.download

import java.lang.reflect.Modifier
import java.net.URI
import java.util.Locale

internal data class VideoSource(
    val urls: List<String>, val width: Int? = null, val height: Int? = null,
    val bytes: Long? = null, val original: Boolean = false,
) {
    fun detail(includeSize: Boolean = true): String = buildList {
        if (width != null && height != null && width > 0 && height > 0) add("$width × $height")
        if (includeSize && bytes != null && bytes > 0) add(
            if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0))
    }.joinToString(" · ")
}

internal data class CaptionSource(val language: String, val urls: List<String>, val original: Boolean)
internal data class CaptionPair(val original: CaptionSource, val translated: CaptionSource)

/** Reads only known media-model properties; never follows arbitrary host object graphs. */
internal object DownloadModels {
    fun property(owner: Any?, vararg names: String): Any? {
        if (owner == null) return null
        for (name in names) {
            val method = owner.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) runCatching { method.invoke(owner) }.getOrNull()?.let { return it }
            var type: Class<*>? = owner.javaClass
            while (type != null && type != Any::class.java) {
                val field = runCatching { type!!.getDeclaredField(name) }.getOrNull()
                if (field != null && !Modifier.isStatic(field.modifiers)) {
                    runCatching { field.isAccessible = true; field.get(owner) }.getOrNull()?.let { return it }
                }
                type = type.superclass
            }
        }
        return null
    }

    fun urls(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Collection<*> -> value.filterIsInstance<String>()
        null -> emptyList()
        else -> urls(property(value, "getUrlList", "urlList", "url_list"))
    }.filter { url -> runCatching {
        val uri = URI(url)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false) }.distinct()

    private fun number(owner: Any?, vararg names: String): Long? =
        (property(owner, *names) as? Number)?.toLong()?.takeIf { it > 0 }

    fun video(aweme: Any): Any? = property(aweme, "getVideo", "video")

    fun sources(aweme: Any): List<VideoSource> {
        if (property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") != null) return emptyList()
        val video = video(aweme) ?: return emptyList()
        val choices = mutableListOf<VideoSource>()
        fun add(model: Any?) {
            if (model == null || listOf("hasDashBitrate", "isDash", "isAudioOnly").any { property(model, it) == true }) return
            val links = urls(model)
            if (links.isEmpty()) return
            // Never attach the main video's dimensions to a different bitrate rendition.
            choices.add(VideoSource(links,
                number(model, "getWidth", "width")?.toInt(), number(model, "getHeight", "height")?.toInt(),
                number(model, "getDataSize", "dataSize", "getSize", "size")))
        }
        (property(video, "getBitRate", "bitRate", "bit_rate") as? Collection<*>)?.forEach { rate ->
            if (listOf("isDash", "isAudioOnly").any { property(rate, it) == true }) return@forEach
            add(property(rate, "getPlayAddr", "playAddr", "play_addr"))
        }
        listOf("getDownloadNoWatermarkAddr", "getPlayAddrH264", "getPlayAddr", "getPlayAddrBytevc1",
            "getRawPlayAddr\$common_model_release").forEach { name -> add(property(video, name)) }
        return choices.distinctBy { it.urls.first() }.sortedByDescending {
            (it.width?.toLong() ?: 0) * (it.height ?: 0)
        }
    }

    fun shareUrl(aweme: Any): String? {
        val id = property(aweme, "getAid", "getAwemeId", "aid")?.toString()
            ?.takeIf { it.matches(Regex("[0-9]{5,30}")) } ?: return null
        val handle = property(property(aweme, "getAuthor", "author"), "getUniqueId", "uniqueId")
            ?.toString()?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,64}")) } ?: "_"
        return "https://www.tiktok.com/@$handle/video/$id"
    }

    fun captions(aweme: Any): List<CaptionSource> {
        val video = video(aweme) ?: return emptyList()
        val model = property(video, "getCaptionModel", "captionModel", "getClaInfo", "claInfo")
        val items = property(model, "getCaptionList", "captionList", "getCaptionInfos", "captionInfos")
            as? Collection<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val links = (urls(property(item, "getUrl", "url")) +
                urls(property(item, "getUrlList", "urlList"))).distinct()
            val isOriginal = property(item, "isOriginalCaption", "getIsOriginalCaption") as? Boolean
                ?: return@mapNotNull null // Do not guess which language is the original.
            val language = property(item, "getLanguageCode", "languageCode", "getLang", "lang")
                ?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (links.isEmpty()) null else CaptionSource(language, links, isOriginal)
        }.distinctBy { Triple(it.language, it.original, it.urls.first()) }
    }

    /** Target language is explicit when the host supplies it; otherwise offer its official tracks. */
    fun pair(tracks: List<CaptionSource>, targetLanguage: String): CaptionPair? {
        val original = tracks.firstOrNull { it.original } ?: return null
        val translated = tracks.filter { !it.original && it.language != original.language }
        val target = translated.firstOrNull { it.language.equals(targetLanguage, true) }
            ?: translated.firstOrNull { it.language.substringBefore('-').equals(targetLanguage.substringBefore('-'), true) }
            ?: translated.singleOrNull() ?: return null
        return CaptionPair(original, target)
    }
}
