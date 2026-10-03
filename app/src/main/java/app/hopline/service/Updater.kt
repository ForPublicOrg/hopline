package app.hopline.service

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import app.hopline.R
import app.hopline.core.Update
import app.hopline.core.Update.Release
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Hopline keeping itself up to date from its own GitHub releases — and never more than offering.
 *
 * By itself (while "Update Hopline automatically" is on) it asks GitHub a few times a day whether
 * a newer version is out, and on a network that isn't metered it fetches it and checks it is
 * exactly what was published, signed with the same key as the Hopline on this phone. Then it
 * waits. Installing is always the person's own tap, because it stops the mesh for a moment — and
 * nobody is ever made to: one banner on Home that can be closed for good, one notification per
 * version, and Settings for whoever wants it later. A phone that never updates keeps working
 * exactly as it does.
 *
 * Threads: everything here runs on the main thread, like [Core], except the work marked "worker"
 * — the one background thread that does the asking, fetching, checking and handing over, one job
 * at a time and at the lowest priority, so the mesh never waits on any of it.
 *
 * What must outlive the process is in its own preferences file; what is merely under way (a
 * check, a download, an install waiting for Android's question) is not, and a restarted process
 * simply finds itself at the last settled state ([settled]).
 */
object Updater {
    private const val TAG = "Hopline/Updater"

    /** Why an update didn't happen, as the person is told it (the words are in ui/UpdateCard). */
    enum class Why { NETWORK, SPACE, FETCH, MISMATCH, SIGNER, UNUSABLE, REFUSED, INSTALL }

    sealed class Status {
        /** The newer version this state is about, if it is about one. */
        open val release: Release? get() = null

        object Idle : Status()
        object Checking : Status()
        /** Out, and waiting for a tap on "Download": the network is metered, or the switch is off. */
        class Available(override val release: Release) : Status()
        class Downloading(override val release: Release, val done: Long, val total: Long) : Status()
        /** Fetched and checked: one tap on "Install" away. */
        class Ready(override val release: Release) : Status()
        class Installing(override val release: Release) : Status()
        /** [retry]: trying again can help (the network, a full phone). Otherwise only the web page can. */
        class Failed(override val release: Release, val why: Why, val retry: Boolean) : Status()
    }

    /**
     * False in a build that can be debugged: it is signed with a debug key, so a release APK could
     * never replace it. Nothing is checked or fetched then, and Settings says so.
     */
    var enabled = false; private set
    var status: Status = Status.Idle; private set
    /** The Settings switch. Off: Hopline neither checks nor downloads by itself. */
    var auto = true; private set
    /** When GitHub last answered a check; 0 = never. */
    var lastOkAt = 0L; private set
    /**
     * Did that last answer name a release Hopline could read? False when it named one it could
     * not use — its APK attached under another name, a tag that isn't a version. The check still
     * counts for pacing, but "Up to date" is then more than Hopline knows, and Settings doesn't say it.
     */
    var sure = true; private set

    private lateinit var app: Application
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hopline-update").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private var lastTryAt = 0L
    private var fails = 0
    /**
     * "Check for updates" was tapped with no internet, and was told Hopline will check once there
     * is some. Kept across a restart: the promise was made to the person, not to this process.
     */
    private var owed = false
    /** The look at GitHub under way, so that a tap on Download or Install can cut it short ([takeOver]). */
    private var checking: Fetch.Session? = null
    /** The newer version on offer, as GitHub last described it. */
    private var release: Release? = null
    /** [Release.asset] of the file that is downloaded and verified, and its SHA-256 as it was then. */
    private var readyAsset = ""
    private var readySha = ""
    /** [Release.asset] of a file that must not be fetched or offered again, and why. */
    private var bad = ""
    private var badWhy = ""
    private var notifiedVersion = ""
    /** What the person closed the banner on: a version, or the one file a "couldn't update" was about ([Update.dismissed]). */
    private var dismissedVersion = ""
    /** Downloads Hopline started by itself that didn't finish, and when the last one began: its own back-off. */
    private var dlFails = 0
    private var dlTryAt = 0L

    private var download: Fetch.Session? = null
    private var downloadByPerson = false
    /** The install session under way (-1: none), written by the worker before Android can report on it. */
    @Volatile private var installId = -1
    /** Sessions given up for a newer try: what Android still says about them is no longer news. */
    private val stale: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private var installingSince = 0L
    /** Android has put its "update this app?" question up, and hasn't told us the answer. */
    private var awaitingAnswer = false

    /** The version running on this phone, as Android names it. "?" — never out of date — if it won't say. */
    val installedVersion: String by lazy { try { packageInfo(0).versionName ?: "?" } catch (e: Exception) { "?" } }

    // ------------------------------------------------------------------ start

    fun init(application: Application) {
        app = application
        enabled = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
        if (!enabled) return
        try {
            load()
            // What was on offer is what runs now (or older): the update happened — here, or by hand.
            if (release?.let { Update.isNewer(it.version, installedVersion) } == false) forget()
            status = settled()
            val ready = (status as? Status.Ready)?.release
            worker.execute { tidy(ready) }
        } catch (e: Exception) { Log.w(TAG, "could not read the update state", e) }
    }

    private fun load() {
        val p = prefs
        auto = p.getBoolean(K_AUTO, true)
        lastOkAt = p.getLong(K_OK, 0); lastTryAt = p.getLong(K_TRY, 0); fails = p.getInt(K_FAILS, 0)
        sure = p.getBoolean(K_SURE, true); owed = p.getBoolean(K_OWED, false)
        dlFails = p.getInt(K_DL_FAILS, 0); dlTryAt = p.getLong(K_DL_TRY, 0)
        readyAsset = p.getString(K_READY, "").orEmpty(); readySha = p.getString(K_READY_SHA, "").orEmpty()
        bad = p.getString(K_BAD, "").orEmpty(); badWhy = p.getString(K_BAD_WHY, "").orEmpty()
        notifiedVersion = p.getString(K_NOTIFIED, "").orEmpty(); dismissedVersion = p.getString(K_DISMISSED, "").orEmpty()
        val version = p.getString(K_VERSION, "").orEmpty()
        val url = p.getString(K_URL, "").orEmpty()
        val size = p.getLong(K_SIZE, 0)
        // Read back as strictly as it was first read: the version becomes part of a file name, the address is fetched from.
        release = if (Update.versionOf(version) != version || size <= 0 || !Update.allowedHost(url)) null
            else Release(version, url, size, p.getString(K_SHA, "").orEmpty(), p.getString(K_SUMMARY, "").orEmpty(),
                p.getString(K_PAGE, "").orEmpty().takeIf { Update.allowedHost(it) } ?: Update.RELEASES_PAGE)
    }

    /** Where things stand when nothing is under way — also what a restarted process starts from. */
    private fun settled(): Status {
        val rel = release ?: return Status.Idle
        return when (rel.asset) {
            bad -> Status.Failed(rel, try { Why.valueOf(badWhy) } catch (e: Exception) { Why.UNUSABLE }, retry = false)
            readyAsset -> Status.Ready(rel)
            else -> Status.Available(rel)
        }
    }

    private fun set(s: Status) { status = s; Core.changed() }

    private fun doing(): Update.Doing = when (status) {
        is Status.Checking -> Update.Doing.CHECKING
        is Status.Downloading -> Update.Doing.DOWNLOADING
        is Status.Installing -> Update.Doing.INSTALLING
        else -> Update.Doing.NOTHING
    }

    /** Something is under way that nothing Hopline does by itself may cut across ([Update.mayStartByItself]). */
    private fun busy(): Boolean = !Update.mayStartByItself(doing())

    /**
     * A tap on Download or Install came while GitHub was being asked ([Update.tapMayStart]): the
     * tap goes first. The look is cut short — the one worker thread is free for what was tapped —
     * and its answer, should one still arrive, is nobody's ([checked]). Nothing about it is
     * counted, so the look is simply due again.
     */
    private fun takeOver() {
        checking?.abort()
        checking = null
    }

    /**
     * What Home's banner shows, or null for none: a state about a newer version that the person
     * hasn't said "not now" to. A check in between changes nothing on Home — the banner that was
     * there stays while GitHub is asked again, and its button works all the while ([takeOver]).
     */
    fun banner(): Status? {
        val s = if (status is Status.Checking) settled() else status
        return s.takeIf { it.release?.let { r -> !Update.dismissed(dismissedVersion, r) } == true }
    }

    /**
     * "Not now": Home's banner stays away for this version for good, and its notification goes.
     * Nothing else changes — a download under way finishes, and Settings still offers the version.
     *
     * Closing a "couldn't update" that trying again can't help is different: that says "not now"
     * to the file that failed, not to the version. If a corrected file is published under the same
     * version, it gets its banner and its one notification like any other ([Update.dismissed]).
     */
    fun dismiss() {
        val s = if (status is Status.Checking) settled() else status
        val rel = s.release ?: return
        dismissedVersion = if (s is Status.Failed && !s.retry) rel.asset else rel.version
        prefs.edit().putString(K_DISMISSED, dismissedVersion).apply()
        Notifications.cancelUpdate(app)
        Core.changed()
    }

    fun setAuto(on: Boolean) {
        if (!enabled || on == auto) return
        auto = on
        prefs.edit().putBoolean(K_AUTO, on).apply()
        // Switched off in the middle of a download nobody asked for: that one stops too.
        if (!on && status is Status.Downloading && !downloadByPerson) download?.abort()
        Core.changed()
        if (on) maybeCheck()
    }

    // ------------------------------------------------------------------ by itself

    /**
     * Do whatever is due by itself, if anything: fetch a version that is waiting (only on a
     * network that isn't metered, and not on a nearly flat battery), or ask GitHub again. Called
     * often — every tick of the mesh service, whenever internet appears, whenever Home is shown —
     * so everything that costs anything comes after the checks that cost nothing.
     */
    fun maybeCheck() {
        if (!enabled) return
        try {
            if (status is Status.Installing && SystemClock.elapsedRealtime() - installingSince > INSTALL_STUCK_MS) set(settled())
            if (!auto || busy()) return
            val now = System.currentTimeMillis()
            val waiting = (status as? Status.Available)?.release
            val fetch = waiting != null && waiting.size <= Update.MAX_APK_BYTES && (dlTryAt > now || now >= dlTryAt + Update.backoff(dlFails))
            val look = owed || Update.isDue(now, lastOkAt, lastTryAt, fails)
            if (!fetch && !look) return
            // Something is due and there is no internet for it. On a phone with no group on the
            // radio nothing ticks, so nothing would notice the internet coming back: have Android say.
            if (!Core.internetNow()) { Core.watchInternet(); return }
            if (fetch && !metered() && !Core.batteryLow()) download(byPerson = false)
            else if (look) check(byPerson = false)
        } catch (e: Exception) { Log.w(TAG, "maybeCheck", e) }
    }

    /** Mobile data, a phone's hotspot, a WiFi marked as costing money — or anything Android won't vouch for. */
    private fun metered(): Boolean =
        try { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered } catch (e: Exception) { true }

    // ------------------------------------------------------------------ checking

    /**
     * "Check for updates": ask now, whatever the switch says and however recently Hopline asked.
     * With no internet to ask over, what the person may be told is [Update.asked]'s to say: the
     * check is owed, and made as soon as there is internet, only when the switch is on and the
     * phone is really being told when internet returns. Otherwise they are asked to try again.
     */
    fun checkNow(): Update.Asked {
        if (!enabled || busy()) return Update.Asked.CHECKING
        val online = Core.internetNow()
        val asked = Update.asked(online, auto, watching = !online && auto && Core.watchInternet())
        when (asked) {
            Update.Asked.CHECKING -> check(byPerson = true)
            Update.Asked.LATER -> owe(true)
            Update.Asked.NOT_NOW -> { }
        }
        return asked
    }

    private fun owe(on: Boolean) {
        if (owed == on) return
        owed = on
        prefs.edit().putBoolean(K_OWED, on).apply()
    }

    /** GitHub answered. [release] is null when what it named isn't a release Hopline can use. */
    private class Answer(val release: Release?)

    private fun check(byPerson: Boolean) {
        lastTryAt = System.currentTimeMillis()
        prefs.edit().putLong(K_TRY, lastTryAt).apply()
        val session = Fetch.Session()
        checking = session
        set(Status.Checking)
        worker.execute {
            val answer = try {
                val r = Fetch.get(Update.LATEST_URL, maxWire = MAX_ANSWER_BYTES, maxBody = MAX_ANSWER_BYTES * 8, accept = listOf("application/json"),
                    session = session, hostOk = Update::allowedHost)
                Answer(Update.parseLatest(String(r.body, Charsets.UTF_8)))
            } catch (e: Exception) { Log.i(TAG, "check failed: ${e.message}"); null }
            Core.handler.post { checked(session, answer, byPerson) }
        }
    }

    private fun checked(session: Fetch.Session, answer: Answer?, byPerson: Boolean) {
        // A tap took over while GitHub was being asked ([takeOver]): the screen has moved on to
        // what was tapped, and this answer must not put it back, nor sweep away the file being
        // installed. It is dropped before anything is counted — the look is due again as it was.
        if (checking !== session) return
        checking = null
        owe(false)   // the look that was promised has been made, whatever came of it
        if (answer == null) {
            fails = minOf(fails + 1, MAX_COUNTED)
            prefs.edit().putInt(K_FAILS, fails).apply()
            set(settled())
            if (byPerson && Core.appVisible) Toast.makeText(app, R.string.update_check_failed, Toast.LENGTH_LONG).show()
            return
        }
        lastOkAt = System.currentTimeMillis(); fails = 0
        sure = answer.release != null
        prefs.edit().putLong(K_OK, lastOkAt).putInt(K_FAILS, 0).putBoolean(K_SURE, sure).apply()
        val latest = answer.release
        when (Update.found(latest, installedVersion, release)) {
            Update.Found.NOTHING -> forget()
            Update.Found.KEEP -> set(settled())
            // The same file: only what is said about it (its notes, its page) may have been edited.
            Update.Found.SAME -> { keep(latest ?: release); set(settled()) }
            Update.Found.NEW -> adopt(latest!!)
        }
        maybeCheck()   // a version that just turned up is fetched right away, where that is allowed
    }

    private fun keep(rel: Release?) {
        release = rel
        prefs.edit().apply {
            if (rel == null) for (k in listOf(K_VERSION, K_URL, K_SIZE, K_SHA, K_SUMMARY, K_PAGE)) remove(k)
            else putString(K_VERSION, rel.version).putString(K_URL, rel.apkUrl).putLong(K_SIZE, rel.size)
                .putString(K_SHA, rel.sha256).putString(K_SUMMARY, rel.summary).putString(K_PAGE, rel.page)
        }.apply()
    }

    /** A version, or a file, this phone hasn't seen: whatever was fetched for the one before is of no use now. */
    private fun adopt(rel: Release) {
        keep(rel)
        dropReady()
        dlFails = 0; dlTryAt = 0
        prefs.edit().putInt(K_DL_FAILS, 0).putLong(K_DL_TRY, 0).apply()
        Notifications.cancelUpdate(app)   // "… is ready to install" is no longer true of the file it meant
        worker.execute { sweep(null) }
        set(settled())
    }

    private fun dropReady() {
        if (readyAsset.isEmpty() && readySha.isEmpty()) return
        readyAsset = ""; readySha = ""
        prefs.edit().remove(K_READY).remove(K_READY_SHA).apply()
    }

    /**
     * Nothing newer is on offer (any more): this phone runs the latest. Drop what was known and
     * what was fetched for it. Which version's banner the person closed is kept — if that version
     * is ever named again, it is still one they said "not now" to.
     */
    private fun forget() {
        download?.abort()
        keep(null)
        dropReady()
        bad = ""; badWhy = ""; dlFails = 0; dlTryAt = 0
        prefs.edit().remove(K_BAD).remove(K_BAD_WHY).putInt(K_DL_FAILS, 0).putLong(K_DL_TRY, 0).apply()
        Notifications.cancelUpdate(app)
        worker.execute { sweep(null) }
        set(Status.Idle)
    }

    // ------------------------------------------------------------------ downloading

    /** The person tapped "Download": fetch it now, on whatever network the phone is on. */
    fun download() { if (enabled) download(byPerson = true) }

    /** "Try again": fetch it again — or, when it was the install that failed and the file is still here, install again. */
    fun retry() {
        val f = status as? Status.Failed ?: return
        if (!f.retry) return
        if (readyAsset == f.release.asset) install() else download()
    }

    private fun download(byPerson: Boolean) {
        val rel = release ?: return
        // A tap is not made to wait for a look at GitHub that started by itself; what Hopline
        // starts by itself waits for everything.
        val free = if (byPerson) Update.tapMayStart(doing()) else Update.mayStartByItself(doing())
        if (!free || bad == rel.asset || readyAsset == rel.asset) return
        takeOver()
        val session = Fetch.Session()
        download = session; downloadByPerson = byPerson
        dlTryAt = System.currentTimeMillis()
        prefs.edit().putLong(K_DL_TRY, dlTryAt).apply()
        set(Status.Downloading(rel, 0, rel.size))
        worker.execute {
            val got = fetch(rel, session, byPerson)
            Core.handler.post { downloaded(rel, got, byPerson) }
        }
    }

    /** How a download ended: with a verified file ([sha]), or not — and then whether for good ([forGood]) or quietly ([quiet]). */
    private class Got(val sha: String? = null, val why: Why = Why.NETWORK, val forGood: Boolean = false, val quiet: Boolean = false)

    /** Worker. Fetch the APK, then check it is the published one before anyone is offered it. */
    private fun fetch(rel: Release, session: Fetch.Session, byPerson: Boolean): Got {
        val dest = apkFile(rel.version)
        try {
            sweep(null)
            if (!roomFor(rel.size + SPARE_BYTES)) return Got(why = Why.SPACE)
            var toldAt = 0L
            val sha = Fetch.download(rel.apkUrl, dest, Update.MAX_APK_BYTES, Update::allowedHost, session) { done ->
                val t = SystemClock.elapsedRealtime()
                if (t - toldAt >= PROGRESS_MS) {
                    toldAt = t
                    // A download nobody asked for never moves on to mobile data: if the WiFi it began on is gone, it stops.
                    if (!byPerson && metered()) session.abort()
                    Core.handler.post { progress(rel, done) }
                }
            }
            val flaw = flawOf(dest, rel, sha)
            if (flaw == null) return Got(sha = sha)
            Log.w(TAG, "downloaded ${rel.version} refused: $flaw")
            dest.delete()
            return Got(forGood = true, why = when (flaw) {
                Update.Flaw.SIZE, Update.Flaw.DIGEST -> Why.MISMATCH
                Update.Flaw.PACKAGE, Update.Flaw.SIGNER -> Why.SIGNER
                Update.Flaw.UNREADABLE, Update.Flaw.NOT_NEWER -> Why.UNUSABLE
            })
        } catch (p: Fetch.Problem) {
            Log.i(TAG, "download failed: ${p.why}")
            return when {
                p.why == "cancelled" -> Got(quiet = true)
                p.why == "disk" -> Got(why = Why.SPACE)
                // An address GitHub no longer serves it from, or hands it on to: asking again gets the same answer.
                p.permanent -> Got(why = Why.FETCH, forGood = true)
                else -> Got(why = Why.NETWORK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            dest.delete()
            return Got(why = Why.NETWORK)
        }
    }

    private fun progress(rel: Release, done: Long) {
        val s = status as? Status.Downloading ?: return
        if (s.release.asset == rel.asset && done > s.done) set(Status.Downloading(rel, done, rel.size))
    }

    private fun downloaded(rel: Release, got: Got, byPerson: Boolean) {
        download = null
        // Forgotten meanwhile (the app was replaced under the download): the file went with everything else.
        if (status !is Status.Downloading || release?.asset != rel.asset) return
        when {
            got.sha != null -> {
                readyAsset = rel.asset; readySha = got.sha; dlFails = 0
                prefs.edit().putString(K_READY, readyAsset).putString(K_READY_SHA, readySha).putInt(K_DL_FAILS, 0).apply()
                set(Status.Ready(rel))
                tellOnce(rel)
            }
            got.forGood -> markBad(rel, got.why)
            // Stopped, not failed: the switch went off, or the WiFi did. Nothing to report, nothing to count.
            got.quiet -> set(settled())
            else -> {
                dlFails = minOf(dlFails + 1, MAX_COUNTED)
                prefs.edit().putInt(K_DL_FAILS, dlFails).apply()
                // Someone who tapped Download is told; a fetch nobody asked for just waits its turn to try again.
                set(if (byPerson) Status.Failed(rel, got.why, retry = true) else settled())
            }
        }
    }

    /** This file is never fetched or offered again: only the web page is, until GitHub names another. */
    private fun markBad(rel: Release, why: Why) {
        bad = rel.asset; badWhy = why.name
        prefs.edit().putString(K_BAD, bad).putString(K_BAD_WHY, badWhy).apply()
        dropReady()
        Notifications.cancelUpdate(app)
        worker.execute { sweep(null) }
        set(Status.Failed(rel, why, retry = false))
    }

    /** The one notification a version gets — unless the person already said "not now" to it. */
    private fun tellOnce(rel: Release) {
        if (notifiedVersion == rel.version || Update.dismissed(dismissedVersion, rel)) return
        notifiedVersion = rel.version
        prefs.edit().putString(K_NOTIFIED, notifiedVersion).apply()
        // With Hopline on screen the banner is the news; the notification still goes up, without a sound.
        Notifications.updateReady(app, rel.version, rel.summary, quiet = Core.appVisible)
    }

    // ------------------------------------------------------------------ the file

    private fun dir() = File(app.cacheDir, "update")
    /** [version] is digits and dots, nothing else ([Update.versionOf]) — safe as part of a name. */
    private fun apkFile(version: String) = File(dir(), "Hopline-$version.apk")

    /** Worker. Everything in the update folder but [keep] goes: old versions, a download that was cut off. */
    private fun sweep(keep: File?) {
        try { dir().listFiles()?.forEach { if (it != keep) it.deleteRecursively() } } catch (e: Exception) { }
    }

    /**
     * Worker, at start. The fetched file lives in the cache, which Android (or the person) may
     * empty at any time: if it is gone, "ready" isn't true any more and the version is simply out
     * again, to be fetched again.
     */
    private fun tidy(ready: Release?) {
        val file = ready?.let { apkFile(it.version) }?.takeIf { it.isFile && it.length() == ready.size }
        sweep(file)
        if (ready == null || file != null) return
        Core.handler.post {
            if (readyAsset != ready.asset) return@post
            dropReady()
            Notifications.cancelUpdate(app)
            if (status is Status.Ready) set(settled())
            maybeCheck()
        }
    }

    private fun roomFor(bytes: Long): Boolean = try {
        val sm = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.getAllocatableBytes(sm.getUuidForPath(app.cacheDir)) >= bytes
    } catch (e: Exception) { true }   // Android won't say: try, and let the write say so if it doesn't fit

    // ------------------------------------------------------------------ verifying

    private fun packageInfo(flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, flags)

    private fun archiveInfo(file: File, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) app.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") app.packageManager.getPackageArchiveInfo(file.path, flags)
    } catch (e: Exception) { null }

    /**
     * Worker. Is [file] the published [rel], and an update Android would accept over the Hopline
     * that is installed? The rules are [Update.flaw]; this only gathers what Android knows.
     *
     * The signers are asked for both ways at once: before Android 9 there is only the old way,
     * and Android 9 itself reads a file's signers only when asked the old way too. The warning the
     * old way carries is about a bug Android fixed long before the oldest phone Hopline runs on,
     * and every signer is compared here, never just the first.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun flawOf(file: File, rel: Release, sha: String): Update.Flaw? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val theirs = archiveInfo(file, flags)
        val ours = packageInfo(flags)
        // Made for a newer Android than this phone has: Android would refuse it at the last step.
        if (theirs?.applicationInfo?.let { it.minSdkVersion > Build.VERSION.SDK_INT } == true) return Update.Flaw.UNREADABLE
        var archive = theirs?.let { describe(it, oldWay = false) }
        var installed = describe(ours, oldWay = false)
        // Like is compared with like: if the newer way has nothing to say about the file, both are read the old way.
        if (theirs != null && archive != null && archive.signers.isEmpty()) { archive = describe(theirs, oldWay = true); installed = describe(ours, oldWay = true) }
        return Update.flaw(rel, file.length(), sha, archive, installed)
    }

    @Suppress("DEPRECATION")
    private fun describe(info: PackageInfo, oldWay: Boolean): Update.Apk {
        var signers = emptySet<String>()
        var past = emptySet<String>()
        if (!oldWay && Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo
            if (signing != null) {
                signers = sha256s(signing.apkContentsSigners)
                if (!signing.hasMultipleSigners()) past = sha256s(signing.signingCertificateHistory)
            }
        } else signers = sha256s(info.signatures)
        return Update.Apk(info.packageName.orEmpty(), PackageInfoCompat.getLongVersionCode(info), signers, past)
    }

    private fun sha256s(certs: Array<Signature>?): Set<String> =
        certs.orEmpty().mapTo(HashSet()) { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ installing

    /**
     * The person tapped "Install". Only ever from a tap: the update stops the mesh for a moment,
     * and when that happens is theirs to choose. Android asks them once more itself — until
     * Hopline has updated itself this way once, after which Android 12 and later take the tap
     * here as the answer.
     */
    fun install() {
        val rel = release ?: return
        // Also while GitHub is being asked again in the background: the "ready" banner and its
        // button stay on screen through that, and the tap must do what it says ([Update.tapMayStart]).
        if (!enabled || !Update.tapMayStart(doing()) || readyAsset != rel.asset || bad == rel.asset) return
        takeOver()
        Core.flushSave()   // the process ends when the update lands: nothing may be lost with it
        installId = -1; awaitingAnswer = false
        installingSince = SystemClock.elapsedRealtime()
        Core.handler.removeCallbacks(stopWaiting)
        set(Status.Installing(rel))
        val sha = readySha
        worker.execute {
            val how = stage(rel, sha)
            Core.handler.post { staged(rel, how) }
        }
    }

    private enum class Staged { HANDED_OVER, GONE, NO_ROOM, FAILED }

    /**
     * Worker. Copy the verified file into an install session and hand it to Android. The bytes are
     * hashed again on the way in: what Android gets is what was verified, or nothing.
     */
    private fun stage(rel: Release, sha: String): Staged {
        val file = apkFile(rel.version)
        if (!file.isFile || file.length() != rel.size) return Staged.GONE
        val installer = app.packageManager.packageInstaller
        // An earlier try the person walked away from (Android's question left unanswered) still
        // holds a copy of the file. This tap replaces it.
        try {
            for (old in installer.mySessions) { stale += old.sessionId; try { installer.abandonSession(old.sessionId) } catch (e: Exception) { } }
        } catch (e: Exception) { }
        var id = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            params.setSize(file.length())
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            id = installer.createSession(params)
            installId = id
            installer.openSession(id).use { session ->
                val md = MessageDigest.getInstance("SHA-256")
                session.openWrite(Update.APK_NAME, 0, file.length()).use { out ->
                    file.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n) }
                    }
                    session.fsync(out)
                }
                if (!hex(md.digest()).equals(sha, ignoreCase = true)) { session.abandon(); return Staged.GONE }
                session.commit(statusSender(id, rel))
            }
            return Staged.HANDED_OVER
        } catch (e: Exception) {
            Log.w(TAG, "could not hand the update to Android", e)
            if (id != -1) { stale += id; try { installer.abandonSession(id) } catch (x: Exception) { } }
            return if (e is java.io.IOException && !roomFor(rel.size + SPARE_BYTES)) Staged.NO_ROOM else Staged.FAILED
        }
    }

    /**
     * Where Android reports how the install went. Mutable, because Android writes the outcome into
     * it; explicit — it names Hopline's own receiver, which no other app can reach — so that
     * outcome can't be delivered anywhere else, or made up by anyone else.
     */
    private fun statusSender(id: Int, rel: Release): IntentSender {
        val intent = Intent(app, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS).setPackage(app.packageName).putExtra(EXTRA_ASSET, rel.asset)
        return PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)).intentSender
    }

    private fun staged(rel: Release, how: Staged) {
        if (status !is Status.Installing || release?.asset != rel.asset) return
        when (how) {
            Staged.HANDED_OVER -> { }   // Android takes it from here, and says how it went ([installStatus])
            Staged.GONE -> {
                // The file is no longer there, or no longer what was verified: back to "out", and it is fetched again.
                dropReady()
                Notifications.cancelUpdate(app)
                worker.execute { sweep(null) }
                set(settled())
                maybeCheck()
            }
            Staged.NO_ROOM -> set(Status.Failed(rel, Why.SPACE, retry = true))
            Staged.FAILED -> set(Status.Failed(rel, Why.INSTALL, retry = true))
        }
    }

    /** What Android says about an install session. Main thread (a broadcast). */
    private fun installStatus(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(TAG, "install session $id: status $code ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        if (!enabled || id in stale) return
        // Is this the install the person started a moment ago, in this very process?
        val live = status is Status.Installing && id == installId
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val ask = if (live) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) else null
            if (ask == null) {
                // Android's question for an install nobody is waiting on any more (the process was
                // restarted in between): it is not put in front of someone who didn't just tap.
                stale += id
                try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (e: Exception) { }
                if (live) set(settled())
                return
            }
            awaitingAnswer = true
            try { ctx.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Log.w(TAG, "could not show Android's install question", e); set(settled()) }
            return
        }
        if (code == PackageInstaller.STATUS_SUCCESS) { forget(); return }   // rarely seen: the update replaces this process
        val rel = release?.takeIf { it.asset == intent.getStringExtra(EXTRA_ASSET) } ?: return
        when (code) {
            // The person said no (or Android's own checker did): no complaint — the version is still ready.
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (live) set(settled())
            PackageInstaller.STATUS_FAILURE_STORAGE -> if (live) set(Status.Failed(rel, Why.SPACE, retry = true))
            // Android will not put this file over the installed Hopline, now or on a second try.
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> markBad(rel, Why.UNUSABLE)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_BLOCKED -> markBad(rel, Why.REFUSED)
            else -> if (live) set(Status.Failed(rel, Why.INSTALL, retry = true))
        }
    }

    /**
     * A Hopline screen is in front again. If Android's question was up, it no longer is: answered
     * "yes", the update ends this process within moments; answered "no", Android says so. If
     * neither happens — the question was left open behind the Home button — "Installing…" must not
     * stay on screen with nothing to tap, so after a little while the version is simply ready again.
     */
    fun screenShown() {
        if (status !is Status.Installing || !awaitingAnswer) return
        Core.handler.removeCallbacks(stopWaiting)
        Core.handler.postDelayed(stopWaiting, INSTALL_GRACE_MS)
    }

    private val stopWaiting = Runnable { if (status is Status.Installing && awaitingAnswer) set(settled()) }

    /** Android's report on an install session. Reached only through Hopline's own PendingIntent: not exported. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_INSTALL_STATUS) return
            try { installStatus(ctx, intent) } catch (e: Exception) { Log.w(TAG, "install status", e) }
        }
    }

    /**
     * Hopline was just replaced by a newer Hopline — by this updater, or by an APK opened by hand.
     * The update stopped the mesh; this brings it back without anyone having to open the app, and
     * drops everything the old version knew about the update that has now happened.
     */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            try { if (enabled) forget() } catch (e: Exception) { Log.w(TAG, "could not clear the update state", e) }
            try { Core.ensureRunning() } catch (e: Exception) { Log.w(TAG, "could not bring the mesh back", e) }
        }
    }

    private const val PREFS = "hopline_update"
    private const val K_AUTO = "auto"
    private const val K_OK = "lastOkAt"
    private const val K_TRY = "lastTryAt"
    private const val K_FAILS = "fails"
    private const val K_SURE = "lastAnswerUsable"
    private const val K_OWED = "checkOwed"
    private const val K_VERSION = "version"
    private const val K_URL = "apkUrl"
    private const val K_SIZE = "size"
    private const val K_SHA = "sha256"
    private const val K_SUMMARY = "summary"
    private const val K_PAGE = "page"
    private const val K_READY = "readyVersion"
    private const val K_READY_SHA = "readySha256"
    private const val K_BAD = "badVersion"
    private const val K_BAD_WHY = "badWhy"
    private const val K_NOTIFIED = "notifiedVersion"
    private const val K_DISMISSED = "dismissedVersion"
    private const val K_DL_FAILS = "downloadFails"
    private const val K_DL_TRY = "downloadTryAt"

    private const val ACTION_INSTALL_STATUS = "app.hopline.INSTALL_STATUS"
    private const val EXTRA_ASSET = "asset"

    /** GitHub's answer about one release is a few KB; this is far more than it ever needs. */
    private const val MAX_ANSWER_BYTES = 200_000
    /** Free space wanted beyond the file itself before a download starts. */
    private const val SPARE_BYTES = 16L * 1024 * 1024
    /** How often a download's progress reaches the screen. */
    private const val PROGRESS_MS = 400L
    /** Failures in a row are counted no further than this; the waits stopped growing long before. */
    private const val MAX_COUNTED = 20
    /** After Android's question is gone from the screen, how long "Installing…" may stand with no word. */
    private const val INSTALL_GRACE_MS = 15_000L
    /** An install Android never reported on, in any way: stop saying "Installing…". */
    private const val INSTALL_STUCK_MS = 3 * 60_000L
}
