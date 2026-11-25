package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.Net
import dev.kamol1dn.syllable.lyrics.catching
import org.json.JSONObject

/** A newer version published on GitHub. [downloadUrl] is its APK, or its release page when it has none attached. */
data class AppUpdate(val version: String, val downloadUrl: String, val notes: String)

object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/kamol1dn/syllable-lyrics/releases/latest"

    /** The latest release when it is newer than [current], or null: also when GitHub can't be reached. */
    suspend fun newerThan(current: String): AppUpdate? = catching {
        val text = Net.get(LATEST, "Accept" to "application/vnd.github+json") ?: return null
        parse(text)?.takeIf { isNewer(it.version, current) }
    }.getOrNull()

    fun parse(json: String): AppUpdate? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").ifEmpty { return null }
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
        val url = apk ?: o.optString("html_url").takeIf { it.startsWith("https://") } ?: return null
        // Release notes are written in Markdown, which the notice shows as plain text.
        val notes = o.optString("body").replace("**", "").replace("`", "").lines()
            .joinToString("\n") { it.replaceFirst(Regex("""^\s*[-*] """), "• ") }.trim()
        return AppUpdate(version, url, notes)
    }

    /** Compares the numbers of two versions like 1.2.10 and 1.3. Anything after a dash (-preview) is left out. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
