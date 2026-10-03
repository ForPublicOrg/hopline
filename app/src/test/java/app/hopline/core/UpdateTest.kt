package app.hopline.core

import app.hopline.core.Update.Apk
import app.hopline.core.Update.Flaw
import app.hopline.core.Update.Found
import app.hopline.core.Update.Release
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * What Hopline decides about its own updates from text and numbers alone: reading GitHub's answer
 * (a real one, saved), which version is newer, which addresses it will talk to, how often it asks,
 * and whether a downloaded file is the one that was published.
 */
class UpdateTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val latest = fixture("github_release_latest.json")

    /** The saved answer with something changed in it. */
    private fun changed(edit: (JSONObject) -> Unit): String = JSONObject(latest).also(edit).toString()

    private fun JSONObject.apk(): JSONObject {
        val list = getJSONArray("assets")
        return (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("name") == "Hopline.apk" }
    }

    private fun JSONObject.dropAsset(name: String) {
        val list = getJSONArray("assets")
        for (i in list.length() - 1 downTo 0) if (list.getJSONObject(i).getString("name") == name) list.remove(i)
    }

    // ---------------------------------------------------------------- reading GitHub's answer

    @Test fun `a real latest-release answer gives the version, the one right file, and a line about it`() {
        val r = Update.parseLatest(latest)!!
        assertEquals("the tag's v is not part of the version", "2.2", r.version)
        // Four files are attached; only the one called exactly Hopline.apk is Hopline.
        assertEquals("https://github.com/ForPublicOrg/hopline/releases/download/v2.2/Hopline.apk", r.apkUrl)
        assertEquals(7706488L, r.size)
        assertEquals("63f4f3c0b98fa375b98b26215a9616cf51b62a7e9ade40d54792ffaa52b51e3c", r.sha256)
        assertEquals("Reactions like every other chat app, names you can change, and a shared internet you can actually use.", r.summary)
        assertEquals("https://github.com/ForPublicOrg/hopline/releases/tag/v2.2", r.page)
    }

    @Test fun `a draft or a pre-release is not an update`() {
        assertNull(Update.parseLatest(changed { it.put("draft", true) }))
        assertNull(Update.parseLatest(changed { it.put("prerelease", true) }))
    }

    @Test fun `a release with no Hopline apk, or one still being attached, is not an update`() {
        // "hopline.apk" and "Hopline-debug.apk" are still attached: a name that only looks like it is another file.
        assertNull(Update.parseLatest(changed { it.dropAsset("Hopline.apk") }))
        assertNull(Update.parseLatest(changed { it.put("assets", org.json.JSONArray()) }))
        assertNull(Update.parseLatest(changed { it.remove("assets") }))
        assertNull(Update.parseLatest(changed { it.apk().put("state", "starter") }))
        assertNull(Update.parseLatest(changed { it.apk().put("size", 0) }))
        assertNull(Update.parseLatest(changed { it.apk().remove("size") }))
    }

    @Test fun `the tag has to be a version`() {
        fun tagged(tag: String) = Update.parseLatest(changed { it.put("tag_name", tag) })?.version
        assertEquals("2.3", tagged("v2.3"))
        assertEquals("2.3", tagged("V2.3"))
        assertEquals("2.3.1", tagged("2.3.1"))
        assertEquals("3", tagged("v3"))
        assertEquals("2.3.1.4", tagged("v2.3.1.4"))
        for (bad in listOf("", "v", "latest", "v2.3-beta", "2. 3", "v2..3", "2.3.", ".2", "v2.3.1.4.5", "vv2.3", "2,3", "v-2", "99999999999")) {
            assertNull("\"$bad\"", Update.parseLatest(changed { it.put("tag_name", bad) }))
        }
        assertNull(Update.parseLatest(changed { it.remove("tag_name") }))
        assertNull(Update.parseLatest(changed { it.put("tag_name", JSONObject.NULL) }))
    }

    @Test fun `the digest is taken only when it is a sha-256, and a missing one is no reason to refuse`() {
        fun digest(d: Any?) = Update.parseLatest(changed { it.apk().put("digest", d ?: JSONObject.NULL) })!!.sha256
        assertEquals("", digest(null))
        assertEquals("", digest(""))
        assertEquals("", digest("md5:0123456789abcdef0123456789abcdef"))
        assertEquals("", digest("sha256:abc"))
        assertEquals("", digest("sha256:" + "g".repeat(64)))
        assertEquals("ab".repeat(32), digest("sha256:" + "AB".repeat(32)))
        assertEquals("", Update.parseLatest(changed { it.apk().remove("digest") })!!.sha256)
    }

    @Test fun `the download has to be on GitHub, and the page a page of this project`() {
        assertNull(Update.parseLatest(changed { it.apk().put("browser_download_url", "https://example.com/Hopline.apk") }))
        assertNull(Update.parseLatest(changed { it.apk().put("browser_download_url", "http://github.com/ForPublicOrg/hopline/releases/download/v2.2/Hopline.apk") }))
        assertNull(Update.parseLatest(changed { it.apk().remove("browser_download_url") }))
        for (elsewhere in listOf("https://evil.example/releases", "https://github.com/someone/else/releases/tag/v9", "javascript:alert(1)", "")) {
            assertEquals(Update.RELEASES_PAGE, Update.parseLatest(changed { it.put("html_url", elsewhere) })!!.page)
        }
        assertEquals(Update.RELEASES_PAGE, Update.parseLatest(changed { it.remove("html_url") })!!.page)
    }

    @Test fun `the summary is the first line that says something, without its markdown, cut to fit`() {
        fun summary(body: Any?) = Update.parseLatest(changed { it.put("body", body ?: JSONObject.NULL) })!!.summary
        assertEquals("", summary(null))
        assertEquals("", summary(""))
        assertEquals("", summary("\n \n***\n"))
        assertEquals("Leaving a group keeps its chat", summary("\r\n\r\n## Leaving a group *keeps* its `chat`\r\nmore"))
        assertEquals("Fixes", summary("__Fixes__\n- one"))
        val long = summary("word ".repeat(100))
        assertEquals(Update.MAX_SUMMARY, long.codePointCount(0, long.length))
        assertTrue(long.endsWith("…"))
        // Never half an emoji, however the cut falls.
        val emoji = summary("😀".repeat(300))
        assertEquals(Update.MAX_SUMMARY, emoji.codePointCount(0, emoji.length))
        assertFalse(Character.isHighSurrogate(emoji[emoji.length - 2]))
        assertFalse("nothing invisible survives", summary("a‮b\u0007c").any { it == '‮' || it == '\u0007' })
    }

    @Test fun `junk is never an update and never an exception`() {
        for (junk in listOf("", " ", "null", "[]", "{}", "<html>Sign in to this WiFi</html>", "{\"message\":\"API rate limit exceeded\"}",
            "{\"tag_name\":\"v9\"}", "{\"tag_name\":\"v9\",\"assets\":\"no\"}", "{\"tag_name\":\"v9\",\"assets\":[1,null,\"x\"]}", latest.dropLast(40))) {
            assertNull(junk.take(30), Update.parseLatest(junk))
        }
    }

    @Test fun `a replaced file under the same version is a different file`() {
        val a = Update.parseLatest(latest)!!
        val b = Update.parseLatest(changed { it.apk().put("size", 7706489).put("digest", "sha256:" + "cd".repeat(32)) })!!
        assertEquals(a.version, b.version)
        assertNotEquals(a.asset, b.asset)
        assertEquals(a.asset, Update.parseLatest(latest)!!.asset)
    }

    // ---------------------------------------------------------------- which version is newer

    @Test fun `versions compare number by number`() {
        val newer = listOf("2.3" to "2.2", "2.10" to "2.9", "2.3.1" to "2.3", "3" to "2.99.99", "2.2.0.1" to "2.2", "10.0" to "9.9", "2.3" to "2.2.9")
        for ((a, b) in newer) {
            assertTrue("$a > $b", Update.isNewer(a, b))
            assertFalse("$b < $a", Update.isNewer(b, a))
        }
        val same = listOf("2.3" to "2.3", "2.3" to "2.3.0", "2.3.0.0" to "2.3", "02.3" to "2.03")
        for ((a, b) in same) {
            assertFalse("$a = $b", Update.isNewer(a, b))
            assertFalse("$b = $a", Update.isNewer(b, a))
        }
    }

    @Test fun `a version that can't be read is never newer`() {
        for (bad in listOf("", "v2.3", "2.3-beta", "latest", "2.3 ", "2..3", "1.2.3.4.5", "٢.٣", "99999999999999999999")) {
            assertFalse("\"$bad\"", Update.isNewer(bad, "1.0"))
            assertFalse("\"$bad\"", Update.isNewer("9.9", bad))
        }
    }

    // ---------------------------------------------------------------- which addresses

    @Test fun `only GitHub, and only over https`() {
        val yes = listOf(Update.LATEST_URL, Update.RELEASES_PAGE,
            "https://github.com/ForPublicOrg/hopline/releases/download/v2.2/Hopline.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/1/abc?X-Amz-Signature=1",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x%2F",
            "https://GitHub.com/x", "https://github.com:443/x")
        for (u in yes) assertTrue(u, Update.allowedHost(u))
        val no = listOf(
            "http://github.com/ForPublicOrg/hopline/releases/latest", "http://api.github.com/x", "ftp://github.com/x",
            "https://github.com.evil.io/x", "https://evilgithubusercontent.com/x", "https://githubusercontent.com/x",
            "https://api.github.com.evil.io/x", "https://notgithub.com/x", "https://gist.github.com/x", "https://raw.github.com/x",
            "https://github.com@evil.io/x", "https://user:pw@github.com/x", "https://github.com:8443/x", "https://github.com./x",
            "https://evil.io/github.com", "https://evil.io/?u=https://github.com", "https://objects.githubusercontent.com.evil.io/x",
            "https://140.82.112.3/x", "github.com/x", "//github.com/x", "", "https://", "not a url", "https://github .com/x")
        for (u in no) assertFalse(u, Update.allowedHost(u))
    }

    // ---------------------------------------------------------------- how often

    @Test fun `a check is due at once on a phone that never checked, then every six hours`() {
        val h = 3_600_000L
        val t = 1_700_000_000_000L
        assertTrue(Update.isDue(now = t, lastOkAt = 0, lastTryAt = 0, fails = 0))
        assertEquals(t + 6 * h, Update.dueAt(lastOkAt = t, lastTryAt = t, fails = 0))
        assertFalse(Update.isDue(t + 6 * h - 1, t, t, 0))
        assertTrue(Update.isDue(t + 6 * h, t, t, 0))
    }

    @Test fun `after a failure it tries again in half an hour, then waits twice as long, never more than six hours`() {
        val m = 60_000L
        val t = 1_700_000_000_000L
        val waits = (1..8).map { (Update.dueAt(lastOkAt = t - 99 * m, lastTryAt = t, fails = it) - t) / m }
        assertEquals(listOf(30L, 60L, 120L, 240L, 360L, 360L, 360L, 360L), waits)
        assertEquals("a huge count can't overflow into 'now'", 360 * m, Update.backoff(Int.MAX_VALUE))
        assertEquals(0L, Update.backoff(0))
        // The old success no longer matters while checks are failing: the last try does.
        assertFalse(Update.isDue(t + 29 * m, lastOkAt = 0, lastTryAt = t, fails = 1))
        assertTrue(Update.isDue(t + 30 * m, lastOkAt = 0, lastTryAt = t, fails = 1))
    }

    @Test fun `a clock set back does not put the next check off`() {
        val t = 1_700_000_000_000L
        assertTrue(Update.isDue(now = t - 86_400_000L, lastOkAt = t, lastTryAt = t, fails = 0))
        assertTrue(Update.isDue(now = t - 1, lastOkAt = 0, lastTryAt = t, fails = 3))
    }

    // ---------------------------------------------------------------- what a check found

    private fun release(version: String, size: Long = 7_000_000, sha: String = "ab".repeat(32)) =
        Release(version, "https://github.com/ForPublicOrg/hopline/releases/download/v$version/Hopline.apk", size, sha, "", Update.RELEASES_PAGE)

    @Test fun `what a check's answer means for what the phone already knew`() {
        assertEquals(Found.NEW, Update.found(release("2.3"), "2.2", null))
        assertEquals(Found.SAME, Update.found(release("2.3"), "2.2", release("2.3")))
        assertEquals("the file was replaced under the same version", Found.NEW, Update.found(release("2.3", size = 7_000_001), "2.2", release("2.3")))
        assertEquals(Found.NEW, Update.found(release("2.4"), "2.2", release("2.3")))
        assertEquals("2.4 was withdrawn; 2.3 is still newer than this phone", Found.NEW, Update.found(release("2.3"), "2.2", release("2.4")))
        assertEquals(Found.NOTHING, Update.found(release("2.2"), "2.2", null))
        assertEquals(Found.NOTHING, Update.found(release("2.2"), "2.2", release("2.3")))
        assertEquals("a phone running something later than the latest release", Found.NOTHING, Update.found(release("2.3"), "2.4", null))
        assertEquals(Found.KEEP, Update.found(null, "2.2", release("2.3")))
        assertEquals(Found.KEEP, Update.found(null, "2.2", null))
        assertEquals("a version name Hopline can't read is never out of date", Found.NOTHING, Update.found(release("2.3"), "?", null))
    }

    // ---------------------------------------------------------------- a tap is never swallowed

    @Test fun `a tap on Download or Install starts even while GitHub is being asked in the background`() {
        // Home keeps the banner and its button on screen through a check: the button has to work.
        assertTrue(Update.tapMayStart(Update.Doing.NOTHING))
        assertTrue(Update.tapMayStart(Update.Doing.CHECKING))
        // Only a download or an install already under way makes a second tap pointless.
        assertFalse(Update.tapMayStart(Update.Doing.DOWNLOADING))
        assertFalse(Update.tapMayStart(Update.Doing.INSTALLING))
    }

    @Test fun `what Hopline starts by itself waits for everything else`() {
        assertTrue(Update.mayStartByItself(Update.Doing.NOTHING))
        for (busy in listOf(Update.Doing.CHECKING, Update.Doing.DOWNLOADING, Update.Doing.INSTALLING)) assertFalse("$busy", Update.mayStartByItself(busy))
    }

    // ---------------------------------------------------------------- "Check for updates" with no internet

    @Test fun `with no internet a later check is promised only when one will really be made`() {
        assertEquals(Update.Asked.CHECKING, Update.asked(online = true, auto = true, watching = false))
        assertEquals(Update.Asked.CHECKING, Update.asked(online = true, auto = false, watching = false))
        assertEquals(Update.Asked.LATER, Update.asked(online = false, auto = true, watching = true))
        // Nothing is listening for the internet to come back (no group on the radio, and Android
        // would not register the watch): "Hopline will check when you have signal" would be untrue.
        assertEquals(Update.Asked.NOT_NOW, Update.asked(online = false, auto = true, watching = false))
        // The switch is off: Hopline does not check by itself, and must not say it will.
        assertEquals(Update.Asked.NOT_NOW, Update.asked(online = false, auto = false, watching = true))
        assertEquals(Update.Asked.NOT_NOW, Update.asked(online = false, auto = false, watching = false))
    }

    // ---------------------------------------------------------------- "not now"

    @Test fun `closing a version's banner keeps it away for that version, whatever file it is`() {
        assertTrue(Update.dismissed("2.4", release("2.4")))
        assertTrue("also for a file put in its place later", Update.dismissed("2.4", release("2.4", size = 7_000_001, sha = "cd".repeat(32))))
        assertFalse("a newer version shows its banner again", Update.dismissed("2.4", release("2.5")))
        assertFalse(Update.dismissed("2.4", release("2.4.1")))
        assertFalse("nothing was ever closed", Update.dismissed("", release("2.4")))
    }

    @Test fun `closing a couldn't-update banner says not now to that file, not to the version`() {
        val wrong = release("2.4", size = 7_000_000, sha = "ab".repeat(32))
        val corrected = release("2.4", size = 7_000_123, sha = "cd".repeat(32))
        assertTrue("the failure that was closed stays closed, also after a restart", Update.dismissed(wrong.asset, wrong))
        assertFalse("the corrected file under the same version gets its banner", Update.dismissed(wrong.asset, corrected))
        assertFalse(Update.dismissed(wrong.asset, release("2.5")))
        // a version can never be mistaken for a file's key, nor the other way round
        assertNotEquals(wrong.version, wrong.asset)
        assertTrue(wrong.asset.contains('|')); assertFalse(wrong.version.contains('|'))
    }

    // ---------------------------------------------------------------- is the download what was published?

    private val key = setOf("11".repeat(32))
    private val otherKey = setOf("22".repeat(32))
    private val installed = Apk("app.hopline", 7, key)
    private val rel = release("2.3", size = 1000, sha = "ab".repeat(32))
    private fun flaw(size: Long = 1000, sha: String = "ab".repeat(32), archive: Apk? = Apk("app.hopline", 8, key), on: Apk = installed, of: Release = rel) =
        Update.flaw(of, size, sha, archive, on)

    @Test fun `the published file, signed like the installed app and newer than it, may be installed`() {
        assertNull(flaw())
        assertNull("hex is hex, in either case", flaw(sha = "AB".repeat(32)))
    }

    @Test fun `a file of another length or with another sha-256 is refused`() {
        assertEquals(Flaw.SIZE, flaw(size = 999))
        assertEquals(Flaw.SIZE, flaw(size = 1001))
        assertEquals(Flaw.DIGEST, flaw(sha = "ac".repeat(32)))
        assertEquals(Flaw.DIGEST, flaw(sha = ""))
        // GitHub gave no digest for this one: the length, the package and the key still have to be right.
        val bare = release("2.3", size = 1000, sha = "")
        assertNull(flaw(sha = "ac".repeat(32), of = bare))
        assertEquals(Flaw.SIZE, flaw(size = 5, of = bare))
        assertEquals(Flaw.SIGNER, flaw(of = bare, archive = Apk("app.hopline", 8, otherKey)))
    }

    @Test fun `something that is not an app, not Hopline, or not newer is refused`() {
        assertEquals(Flaw.UNREADABLE, flaw(archive = null))
        assertEquals(Flaw.PACKAGE, flaw(archive = Apk("app.hopline.evil", 8, key)))
        assertEquals(Flaw.PACKAGE, flaw(archive = Apk("", 8, key)))
        assertEquals(Flaw.NOT_NEWER, flaw(archive = Apk("app.hopline", 7, key)))
        assertEquals(Flaw.NOT_NEWER, flaw(archive = Apk("app.hopline", 6, key)))
    }

    @Test fun `a file signed with another key is refused`() {
        assertEquals(Flaw.SIGNER, flaw(archive = Apk("app.hopline", 8, otherKey)))
        assertEquals("one right key among two is not the same signers", Flaw.SIGNER, flaw(archive = Apk("app.hopline", 8, key + otherKey)))
        assertEquals(Flaw.SIGNER, flaw(archive = Apk("app.hopline", 8, key), on = Apk("app.hopline", 7, key + otherKey)))
        assertEquals("a history that never held the installed key vouches for nothing", Flaw.SIGNER,
            flaw(archive = Apk("app.hopline", 8, otherKey, pastSigners = otherKey + setOf("33".repeat(32)))))
    }

    @Test fun `a new key the installed key vouched for is accepted, as Android accepts it`() {
        assertNull(flaw(archive = Apk("app.hopline", 8, otherKey, pastSigners = key + otherKey)))
    }

    @Test fun `signers Android could not read refuse nothing by themselves, but everything else still counts`() {
        assertNull(flaw(archive = Apk("app.hopline", 8, emptySet())))
        assertNull(flaw(on = Apk("app.hopline", 7, emptySet())))
        assertEquals(Flaw.PACKAGE, flaw(archive = Apk("other", 8, emptySet())))
        assertEquals(Flaw.NOT_NEWER, flaw(archive = Apk("app.hopline", 7, emptySet())))
        assertEquals(Flaw.DIGEST, flaw(sha = "00".repeat(32), archive = Apk("app.hopline", 8, emptySet())))
    }
}
