package app.hopline.core

import org.json.JSONObject
import java.net.URI

/**
 * What Hopline needs to know to update itself from its own GitHub releases, with no Android in
 * it: how to read GitHub's "latest release", which version is newer, which addresses a download
 * may come from, and how often to ask. Everything here is decided from text and numbers alone, so
 * it is tested on a laptop against an answer GitHub really gave.
 *
 * Nothing in here can make an update happen: it only says what is on offer. Whether to take it is
 * always the person's choice (see service/Updater).
 */
object Update {
    const val REPO = "ForPublicOrg/hopline"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_NAME = "Hopline.apk"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** No Hopline is anywhere near this big; a "Hopline.apk" that is, is not one to fetch unasked. */
    const val MAX_APK_BYTES = 80L * 1024 * 1024
    const val MAX_SUMMARY = 140

    /** After a check that worked, the next one is this much later: a few times a day, no more. */
    const val CHECK_EVERY_MS = 6 * 3_600_000L
    /** After one that didn't, the first retry — then twice as long each time, up to [CHECK_EVERY_MS]. */
    const val RETRY_MS = 30 * 60_000L

    /**
     * A published version: [version] without the tag's "v", where its APK is and how big,
     * [sha256] of the APK as GitHub states it ("" when GitHub gave none), the first line of the
     * release notes, and the web page a person can get it from by hand.
     */
    class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String, val summary: String, val page: String) {
        /**
         * Which file this is, not just which version: a maintainer who attached the wrong APK and
         * then replaced it has published a different file under the same version.
         */
        val asset: String get() = "$version|$size|$sha256"
    }

    /**
     * GitHub's answer to "what is the latest release?", or null when it isn't one Hopline can
     * use: a draft or a pre-release, a tag that isn't a version, no finished asset called exactly
     * Hopline.apk, a download address off GitHub. Never throws — whatever came down the wire.
     */
    fun parseLatest(json: String): Release? = try {
        val j = JSONObject(json)
        val version = versionOf(text(j, "tag_name"))
        val asset = j.optJSONArray("assets")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                // "uploaded" is a finished file; one still on its way up has a name and no bytes yet.
                .firstOrNull { text(it, "name") == APK_NAME && text(it, "state").let { s -> s.isEmpty() || s == "uploaded" } }
        }
        val url = asset?.let { text(it, "browser_download_url") }.orEmpty()
        val size = asset?.optLong("size", -1) ?: -1
        if (j.optBoolean("draft") || j.optBoolean("prerelease") || version == null || asset == null || size <= 0 || !allowedHost(url)) null
        else Release(
            version, url, size,
            sha256 = DIGEST.matchEntire(text(asset, "digest"))?.groupValues?.get(1)?.lowercase().orEmpty(),
            summary = summaryOf(text(j, "body")),
            // Only ever a page of this project: it is opened in the browser on the person's tap.
            page = text(j, "html_url").takeIf { it.startsWith("https://github.com/$REPO/releases/") && allowedHost(it) } ?: RELEASES_PAGE,
        )
    } catch (e: Exception) { null }

    /** A JSON null is no text at all — not the word "null", which Android's parser would hand back. */
    private fun text(j: JSONObject, key: String): String = if (j.isNull(key)) "" else j.optString(key)

    private val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")
    // [0-9], not \d: on Android \d also takes digits of other scripts, and a version is plain numbers.
    private val VERSION = Regex("""[0-9]{1,9}(\.[0-9]{1,9}){0,3}""")

    /** "v2.3" -> "2.3". Null unless what is left is one to four dotted numbers. */
    internal fun versionOf(tag: String): String? {
        val v = tag.trim().let { if (it.startsWith("v") || it.startsWith("V")) it.substring(1) else it }
        return v.takeIf { VERSION.matches(it) }
    }

    /**
     * What a release is about, in one line: the first line of its notes that says anything, with
     * the markdown marks taken off, nothing invisible left in it, and cut to fit a banner.
     */
    internal fun summaryOf(body: String): String {
        for (raw in body.lineSequence()) {
            val line = Names.clean(raw.filter { it !in "*_#`" }, MAX_SUMMARY * 4)
            if (line.isEmpty()) continue
            if (line.codePointCount(0, line.length) <= MAX_SUMMARY) return line
            return line.substring(0, line.offsetByCodePoints(0, MAX_SUMMARY - 1)).trimEnd() + "…"
        }
        return ""
    }

    /**
     * Is [candidate] a later version than [installed]? Number by number, so 2.10 is after 2.9 and
     * 2.3 is the same as 2.3.0. Anything that isn't plain dotted numbers is never "newer": a
     * version Hopline can't read must not look like an update.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Long>? = v.takeIf { VERSION.matches(it) }?.split('.')?.map { it.toLong() }

    /**
     * May the updater talk to this address? HTTPS only, and only GitHub: its API, the site itself,
     * and the *.githubusercontent.com hosts a release download is handed on to. Checked for the
     * first address and again for every redirect — a look-alike (github.com.evil.io) is not GitHub.
     */
    fun allowedHost(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        if (!"https".equals(u.scheme, ignoreCase = true) || u.rawUserInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        val host = u.host?.lowercase() ?: return false
        return host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com")
    }

    /**
     * When the next check by itself is due: [CHECK_EVERY_MS] after the last one that worked; after
     * [fails] in a row that didn't, [RETRY_MS] after the last try, doubling up to [CHECK_EVERY_MS].
     * A phone that has never checked is due at once.
     */
    fun dueAt(lastOkAt: Long, lastTryAt: Long, fails: Int): Long =
        if (fails <= 0) lastOkAt + CHECK_EVERY_MS else lastTryAt + backoff(fails)

    /** How long to hold off after [fails] failures in a row (of a check, or of a download). */
    fun backoff(fails: Int): Long =
        if (fails <= 0) 0 else minOf(RETRY_MS shl minOf(fails - 1, 10), CHECK_EVERY_MS)

    /**
     * [dueAt], against the clock — and a phone whose clock was set back since the last check (its
     * stamps are in the future) is simply due: it must not wait out the difference.
     */
    fun isDue(now: Long, lastOkAt: Long, lastTryAt: Long, fails: Int): Boolean =
        lastOkAt > now || lastTryAt > now || now >= dueAt(lastOkAt, lastTryAt, fails)

    // ------------------------------------------------------------------ what may start, and what a tap is told

    /** What the updater is in the middle of. */
    enum class Doing { NOTHING, CHECKING, DOWNLOADING, INSTALLING }

    /**
     * May a tap on Download or Install start now? Also while GitHub is being asked for the latest
     * version: that look happens by itself, in the background, and the banner with its button
     * stays on screen all the while — a button that then did nothing would be a broken button. The
     * tap takes over, and the look is made again later. Only a download or an install already
     * under way makes a tap wait: there is nothing a second one could add.
     */
    fun tapMayStart(doing: Doing): Boolean = doing == Doing.NOTHING || doing == Doing.CHECKING

    /** May Hopline start a check or a download by itself? Only when nothing at all is under way. */
    fun mayStartByItself(doing: Doing): Boolean = doing == Doing.NOTHING

    /** What "Check for updates" came to. */
    enum class Asked {
        /** There is internet: GitHub is being asked now (or already was). */
        CHECKING,
        /** No internet — and Hopline will ask by itself the moment there is some. */
        LATER,
        /** No internet, and nothing will ask later: the person has to try again. */
        NOT_NOW,
    }

    /**
     * "Check for updates" was tapped. With no internet, "Hopline will check when you have signal"
     * may only be promised when it will: the switch for checking by itself is on ([auto]), and the
     * phone is really being told when internet comes back ([watching] — with no group on the
     * radio no service runs, and nothing else would notice).
     */
    fun asked(online: Boolean, auto: Boolean, watching: Boolean): Asked = when {
        online -> Asked.CHECKING
        auto && watching -> Asked.LATER
        else -> Asked.NOT_NOW
    }

    /**
     * Has the person said "not now" to what [rel] offers? [key] is what closing the banner stored:
     * the version, for a banner that offered it ("is out", "is ready") — that version's banner
     * then stays away for good — or the one file ([Release.asset]), for a banner that only said
     * that file could not be used. Closing a failure is not declining the version: a corrected
     * file published under the same version gets its banner and its notification. (A version is
     * digits and dots; a file's key has bars in it — the two can't be mistaken for each other.)
     */
    fun dismissed(key: String, rel: Release): Boolean = key.isNotEmpty() && (key == rel.version || key == rel.asset)

    // ------------------------------------------------------------------ what a check found

    /** What an answer from GitHub means for a phone, given what it already knew. */
    enum class Found {
        /** This phone runs the latest version, or a later one: whatever was known is stale. */
        NOTHING,
        /** No release Hopline can use was named (its APK is still being attached, say): what was known stands. */
        KEEP,
        /** The very file this phone already knows about: nothing to fetch a second time. */
        SAME,
        /** A version — or, under the same version, a file — this phone hasn't seen: start over with it. */
        NEW,
    }

    /**
     * [latest] is what GitHub just named (null when it named nothing usable), [installed] the
     * version running here, [known] the newer release this phone had in mind until now.
     */
    fun found(latest: Release?, installed: String, known: Release?): Found = when {
        latest == null -> Found.KEEP
        !isNewer(latest.version, installed) -> Found.NOTHING
        known != null && known.asset == latest.asset -> Found.SAME
        else -> Found.NEW
    }

    // ------------------------------------------------------------------ is the download what was published?

    /**
     * What Android reads out of an APK — a downloaded one, or the one that is installed.
     * [signers] are the SHA-256s of the certificates it is signed with now; [pastSigners] every
     * certificate it has proved it may be signed with (its own included), when its maker has
     * changed keys. Both are empty when Android could not say.
     */
    class Apk(val packageName: String, val versionCode: Long, val signers: Set<String>, val pastSigners: Set<String> = emptySet())

    /** Why a downloaded file must never be offered for installing. */
    enum class Flaw { SIZE, DIGEST, UNREADABLE, PACKAGE, NOT_NEWER, SIGNER }

    /**
     * Everything that has to hold before "Install" is offered, or the first thing that doesn't:
     * the file is exactly as long as GitHub said and (when GitHub gave one) has its SHA-256; it
     * is an app Android can read; it is Hopline; it is a later build than the one installed; and
     * it is signed like the one installed — by the same key, or by a newer key that the installed
     * one's key has vouched for.
     *
     * When Android can't name the signers of either, that alone refuses nothing: Android itself
     * never lets an app be replaced by one signed with another key, whatever Hopline thinks.
     */
    fun flaw(release: Release, size: Long, sha256: String, archive: Apk?, installed: Apk): Flaw? = when {
        size != release.size -> Flaw.SIZE
        release.sha256.isNotEmpty() && !release.sha256.equals(sha256, ignoreCase = true) -> Flaw.DIGEST
        archive == null -> Flaw.UNREADABLE
        archive.packageName != installed.packageName -> Flaw.PACKAGE
        archive.versionCode <= installed.versionCode -> Flaw.NOT_NEWER
        archive.signers.isEmpty() || installed.signers.isEmpty() -> null
        archive.signers == installed.signers || archive.pastSigners.containsAll(installed.signers) -> null
        else -> Flaw.SIGNER
    }
}
