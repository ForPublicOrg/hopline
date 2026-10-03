package app.hopline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.Toast
import app.hopline.R
import app.hopline.core.Update
import app.hopline.service.Updater
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * How Hopline's own update is put to the person — worked out once, for both places it shows: the
 * banner on Home and the About card in Settings. Each state is a title, a line under it, perhaps
 * a progress bar and at most two buttons. It offers; nothing in it asks twice or insists.
 */
object UpdateCard {
    /** What a button does. */
    enum class Action(val label: Int) {
        DOWNLOAD(R.string.update_download), INSTALL(R.string.update_install), RETRY(R.string.update_try_again), PAGE(R.string.update_open_page),
    }

    /**
     * One state as shown. A plain value: equal when nothing a person would see has changed.
     * [permille] is the download so far in thousandths, or -1 for no bar. [spoken] is what
     * TalkBack reads for the title and body together; while downloading it changes only at each
     * quarter, so a screen reader says "25%… 50%…" and not every tick of the bar.
     */
    data class Shown(val version: String, val title: String, val body: String, val permille: Int, val primary: Action?, val secondary: Action?, val spoken: String)

    /** [s] in words and buttons, or null when it isn't about a newer version (nothing known, or still asking). */
    fun of(ctx: Context, s: Updater.Status): Shown? {
        val rel = s.release ?: return null
        val v = rel.version
        fun shown(title: String, body: String, primary: Action? = null, secondary: Action? = null, permille: Int = -1, spoken: String = "$title. $body") =
            Shown(v, title, body, permille, primary, secondary, spoken)
        return when (s) {
            is Updater.Status.Available -> shown(ctx.getString(R.string.update_available, v),
                ctx.getString(R.string.update_available_body, size(ctx, rel.size)) + (if (rel.summary.isNotEmpty()) "\n" + rel.summary else ""), Action.DOWNLOAD)
            is Updater.Status.Downloading -> {
                val total = maxOf(s.total, 1L)
                val done = s.done.coerceIn(0L, total)
                val permille = (done * 1000 / total).toInt()
                val title = ctx.getString(R.string.update_downloading, v)
                shown(title, ctx.getString(R.string.update_progress, size(ctx, done), size(ctx, total)), permille = permille,
                    spoken = title + " " + ctx.getString(R.string.update_progress_spoken, permille / 250 * 25))
            }
            is Updater.Status.Ready -> shown(ctx.getString(R.string.update_ready, v), rel.summary.ifEmpty { ctx.getString(R.string.update_ready_body) }, Action.INSTALL)
            is Updater.Status.Installing -> shown(ctx.getString(R.string.update_installing_title, v), ctx.getString(R.string.update_installing))
            is Updater.Status.Failed -> buttons(s.why, s.retry).let { (primary, secondary) ->
                shown(ctx.getString(R.string.update_failed, v), ctx.getString(reason(s.why)), primary, secondary)
            }
            Updater.Status.Idle, Updater.Status.Checking -> null
        }
    }

    /**
     * The buttons under "Couldn't update": first and second, either may be none. Where trying
     * again can help, that comes first, and the download page is there as the way round.
     *
     * Not for a file signed with another key than the Hopline on this phone. That is the one
     * failure where Hopline knows the page is handing out a file it must not trust — and the same
     * file fetched by hand can't be installed over this Hopline either. All the button could lead
     * to is uninstalling Hopline to make room for it: every chat gone, and a build from another
     * key in its place. So no button; the words say to wait, and a corrected file is offered the
     * usual way when it is published.
     */
    fun buttons(why: Updater.Why, retry: Boolean): Pair<Action?, Action?> = when {
        retry -> Action.RETRY to Action.PAGE
        why == Updater.Why.SIGNER -> null to null
        else -> Action.PAGE to null
    }

    /** Which sentence Settings → About shows about updates. */
    enum class Line { DEBUG, CHECKING, AVAILABLE, STATE, CURRENT, UNSURE, NEVER, OFF }

    /**
     * The update line of Settings → About. [enabled]: this build can update itself at all.
     * [checking]: GitHub is being asked. [available]: a newer version is out and waits for a tap.
     * [known]: any other state about a newer version (it is then worded by [of]). [checked]:
     * GitHub has answered a check at some time. [sure]: its last answer named a release Hopline
     * could read. [auto]: Hopline checks by itself.
     *
     * "Up to date" is said only when Hopline knows it: GitHub's latest release was read, and is
     * not newer. An answer it could not read (the APK attached under another name, a tag that is
     * not a version) may well be a newer version — then the line says only what was found.
     */
    fun line(enabled: Boolean, checking: Boolean, available: Boolean, known: Boolean, checked: Boolean, sure: Boolean, auto: Boolean): Line = when {
        !enabled -> Line.DEBUG
        checking -> Line.CHECKING
        available -> Line.AVAILABLE
        known -> Line.STATE
        checked && sure -> Line.CURRENT
        checked -> Line.UNSURE
        auto -> Line.NEVER
        else -> Line.OFF
    }

    private fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    private fun reason(why: Updater.Why): Int = when (why) {
        Updater.Why.NETWORK -> R.string.update_fail_network
        Updater.Why.SPACE -> R.string.update_fail_space
        Updater.Why.FETCH -> R.string.update_fail_fetch
        Updater.Why.MISMATCH -> R.string.update_fail_mismatch
        Updater.Why.SIGNER -> R.string.update_fail_signer
        Updater.Why.UNUSABLE -> R.string.update_fail_unusable
        Updater.Why.REFUSED -> R.string.update_fail_refused
        Updater.Why.INSTALL -> R.string.update_fail_install
    }

    /**
     * The bar and the buttons of either place ([shown] null: none of them). A button with nothing
     * to do is taken away, not greyed out, and the row they sit in goes with the last of them.
     */
    fun bind(shown: Shown?, bar: LinearProgressIndicator, actions: View, primary: Button, secondary: Button, act: (Action) -> Unit) {
        val permille = shown?.permille ?: -1
        if (permille >= 0) bar.setProgressCompat(permille, bar.visibility == View.VISIBLE)
        bar.visibility = if (permille >= 0) View.VISIBLE else View.GONE
        button(primary, shown?.primary, act)
        button(secondary, shown?.secondary, act)
        actions.visibility = if (shown?.primary != null || shown?.secondary != null) View.VISIBLE else View.GONE
    }

    private fun button(b: Button, action: Action?, act: (Action) -> Unit) {
        b.visibility = if (action != null) View.VISIBLE else View.GONE
        if (action == null) { b.setOnClickListener(null); return }
        b.setText(action.label)
        b.setOnClickListener { act(action) }
    }

    /** Do what a button says. Downloading and installing only ever start here — from a tap. */
    fun run(ctx: Context, action: Action) {
        when (action) {
            Action.DOWNLOAD -> Updater.download()
            Action.INSTALL -> Updater.install()
            Action.RETRY -> Updater.retry()
            Action.PAGE -> {
                val page = Updater.status.release?.page ?: Update.RELEASES_PAGE
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
                catch (e: Exception) { Toast.makeText(ctx, R.string.update_no_browser, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago" — for "Up to date · checked …". */
    fun ago(ctx: Context, then: Long, now: Long = System.currentTimeMillis()): String {
        val gone = (now - then).coerceAtLeast(0)
        val minutes = (gone / 60_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> ctx.getString(R.string.update_checked_now)
            hours < 1 -> ctx.resources.getQuantityString(R.plurals.update_checked_minutes, minutes, minutes)
            days < 1 -> ctx.resources.getQuantityString(R.plurals.update_checked_hours, hours, hours)
            else -> ctx.resources.getQuantityString(R.plurals.update_checked_days, days, days)
        }
    }
}
