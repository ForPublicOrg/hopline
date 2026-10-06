package app.hopline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.SmsText
import app.hopline.mesh.Errand
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared-internet words for screens: a request's live state, why it couldn't be done, when it
 * expires — in plain English, worked out from what the router knows right now. The hub and the
 * reader both use these, so a request never reads one way on one screen and another way on the next.
 */
object NetText {

    fun icon(e: Errand): String = when (e.type) {
        Errand.READ -> "🌐"
        Errand.FIND -> "🔍"
        Errand.WX -> "⛅"
        Errand.SEND -> if (Errand.isEmailTarget(e.args)) "✉️" else "💬"
        else -> "🌐"
    }

    fun kind(ctx: Context, e: Errand): String = ctx.getString(when (e.type) {
        Errand.READ -> R.string.net_kind_page
        Errand.FIND -> R.string.net_kind_search
        Errand.WX -> R.string.net_kind_weather
        Errand.SEND -> if (Errand.isEmailTarget(e.args)) R.string.net_kind_email else R.string.net_kind_text
        else -> R.string.net_kind_other
    })

    /** "14:05" today; "tomorrow 08:00", "yesterday 22:10", "Mon 09:00" or "12 Aug 09:00" otherwise — in the phone's 12/24 h style. */
    fun clock(ctx: Context, ts: Long): String {
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ts))
        fun midnight(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Rounded, not truncated: a day with a clock change is 23 or 25 hours long.
        val days = Math.round((midnight(ts) - midnight(System.currentTimeMillis())) / 86_400_000.0).toInt()
        return when {
            days == 0 -> time
            days == 1 -> ctx.getString(R.string.net_tomorrow_at, time)
            days == -1 -> ctx.getString(R.string.net_yesterday_at, time)
            days in -6..6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts)) + " " + time
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts)) + " " + time
        }
    }

    private fun someone(ctx: Context) = ctx.getString(R.string.net_someone)

    /** Who answered — the name the answer itself carries (in a race, that may not be the phone that claimed it). */
    fun answeredBy(ctx: Context, r: Router, e: Errand): String =
        Names.clean(e.answeredBy).ifEmpty { e.helper?.let { Ui.nameOf(r, it, e.helperName) } ?: someone(ctx) }

    /** The one line under a request's title: what is happening to it right now, honestly. */
    fun status(ctx: Context, r: Router, e: Errand): String = when (e.status) {
        Errand.WAITING -> waiting(ctx, r, e)
        Errand.ASKED -> {
            val h = e.helper
            when {
                h != null && h in e.legacyAsked -> ctx.getString(R.string.net_st_public_asked, Ui.nameOf(r, h, e.helperName))
                h == r.me.id -> ctx.getString(R.string.net_st_running_here)
                else -> {
                    // "Asked" is only true while a phone that can do it is actually around.
                    val live = r.capableHelpers(e)
                    if (live.isEmpty()) waiting(ctx, r, e)
                    else {
                        val first = e.pick.firstNotNullOfOrNull { id -> live.firstOrNull { it.id == id } } ?: live[0]
                        ctx.getString(R.string.net_st_asking, Ui.nameOf(r, first.id, first.name))
                    }
                }
            }
        }
        Errand.CLAIMED -> {
            val h = e.helper
            val name = h?.let { Ui.nameOf(r, it, e.helperName) } ?: e.helperName.ifEmpty { someone(ctx) }
            when {
                h == r.me.id -> ctx.getString(R.string.net_st_running_here)
                // Gone quiet because they left the group: said as it is.
                isQuiet(e) -> ctx.getString(if (h != null && r.people[h]?.left == true) R.string.net_st_quiet_left else R.string.net_st_quiet, name)
                e.type == Errand.SEND -> ctx.getString(if (Errand.isEmailTarget(e.args)) R.string.net_st_sending_mail else R.string.net_st_sending, name)
                h != null && h in e.legacyAsked -> ctx.getString(R.string.net_st_public_asked, name)
                else -> ctx.getString(R.string.net_st_on_it, name)
            }
        }
        Errand.DONE -> done(ctx, r, e)
        Errand.FAILED -> ctx.getString(R.string.net_st_failed, reason(ctx, e))
        Errand.EXPIRED -> ctx.getString(R.string.net_st_expired)
        Errand.CANCELLED -> ctx.getString(R.string.net_st_cancelled)
        else -> ""
    }

    /** A text whose helper went silent after maybe sending it: only the asker may hand it on. */
    fun isQuiet(e: Errand): Boolean =
        e.type == Errand.SEND && e.status == Errand.CLAIMED && e.why == "quiet" && e.leaseUntil <= System.currentTimeMillis()

    private fun waiting(ctx: Context, r: Router, e: Errand): String {
        if (e.allowPublic) r.legacyHelpers().firstOrNull { it.id !in e.legacyAsked }?.let {
            return ctx.getString(R.string.net_st_public_wait, Ui.nameOf(r, it.id, it.name))
        }
        return if (e.exp > 0) ctx.getString(R.string.net_st_waiting, clock(ctx, e.exp)) else ctx.getString(R.string.net_st_waiting_short)
    }

    private fun done(ctx: Context, r: Router, e: Errand): String {
        val at = clock(ctx, if (e.answeredAt > 0) e.answeredAt else e.ts)
        val by = answeredBy(ctx, r, e)
        val local = e.helper == r.me.id
        val line = when {
            e.type == Errand.SEND -> ctx.getString(R.string.net_st_sent, by, at)
            local -> ctx.getString(R.string.net_st_done_here, at)
            e.answerZ.isEmpty() -> ctx.getString(R.string.net_st_done_public, by, at)
            else -> ctx.getString(R.string.net_st_done, by, at)
        }
        // Whose data it cost is part of the honest story: a friend paid for this answer.
        return if (e.cost > 0 && !local && e.type != Errand.SEND) line + " · " + ctx.getString(R.string.net_st_cost, Blobs.prettySize(e.cost.toLong()))
        else line
    }

    /** Why it couldn't be done. The helper's own words are the most specific; known codes come next. */
    fun reason(ctx: Context, e: Errand): String {
        val own = cached("w", e) {
            // A private answer carries the reason as its text; an older helper's public one as
            // the lines after its title.
            val words = e.answer()?.optString("t") ?: e.result?.substringAfter('\n', "")
            plain(words.orEmpty()).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(240).orEmpty()
        }
        if (own.isNotEmpty()) return own
        val w = e.why
        val id = when {
            w == "bad_url" -> R.string.net_why_bad_url
            w == "unsafe" -> R.string.net_why_unsafe
            w == "bad_type" -> R.string.net_why_bad_type
            w == "too_big" -> R.string.net_why_too_big
            w == "needs_browser" -> R.string.net_why_needs_browser
            w == "no_results" -> R.string.net_why_no_results
            w == "no_location" -> R.string.net_why_no_location
            w == "unsupported" -> R.string.net_why_unsupported
            w == "http_404" || w == "http_410" -> R.string.net_why_not_found
            w.startsWith("http_4") -> R.string.net_why_refused
            w == "redirect" -> R.string.net_why_redirect
            w == "empty" -> R.string.net_why_empty
            else -> R.string.net_why_generic
        }
        return ctx.getString(id)
    }

    private val LINK_MARK = Regex("""[ \t]?\[\d{1,3}]""")

    /** Answer text as a person would copy it: no "# " heading marks, no "[3]" link numbers. */
    fun plain(t: String): String =
        t.lineSequence().joinToString("\n") { it.removePrefix("# ") }.replace(LINK_MARK, "")

    /** Answers are gzip'd; cards redraw on every mesh change. Unpack each answer once (main thread only). */
    private val memo = HashMap<String, String>()

    private fun cached(kind: String, e: Errand, make: () -> String): String {
        val key = "$kind|${e.id}|${e.status}|${e.answeredAt}|${e.answerZ.length}"
        memo[key]?.let { return it }
        if (memo.size > 300) memo.clear()
        return make().also { memo[key] = it }
    }

    /** A few lines of a finished answer for its card. */
    fun preview(e: Errand): String {
        if (e.status != Errand.DONE || e.type == Errand.SEND) return ""
        return cached("p", e) {
            val a = e.answer()
            val text = when {
                a == null -> e.result.orEmpty()
                (a.optJSONArray("r")?.length() ?: 0) > 0 -> a.optString("s").ifEmpty { a.optString("t") }
                else -> a.optString("t")
            }
            plain(text).trim().replace(Regex("\n{2,}"), "\n").take(600)
        }
    }

    /** The search language for Wikipedia: "hi", "en", "he" (not Java's legacy "iw"). */
    fun searchLang(): String =
        Locale.getDefault().toLanguageTag().substringBefore('-').lowercase(Locale.ROOT)
            .takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } } ?: "en"

    /** Messages, opened on [number] with the text filled in. The person taps send — never us. */
    fun smsIntent(number: String, text: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text)

    /** The email app, addressed and filled in. Both the URI and the extras: apps read one or the other. */
    fun mailIntent(to: String, subject: String, text: String): Intent {
        val uri = Uri.parse("mailto:" + Uri.encode(to, "@") + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(text))
        return Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    }

    /** What Copy puts on the clipboard when no app here can send it: the recipient, an email's
     *  subject line, then the words — everything needed to send it by hand, nothing blank. */
    fun sendByHand(to: String, subjectLine: String?, text: String): String =
        listOfNotNull(to, subjectLine, text).filter { it.isNotBlank() }.joinToString("\n\n")

    /** A recipient someone else's phone sent us, checked before it goes anywhere near another app. */
    fun safeRecipient(args: org.json.JSONObject): String? {
        val to = args.optString("to").trim()
        return if (Errand.isEmailTarget(args)) to.takeIf { SmsText.looksLikeEmail(it) } else SmsText.normaliseNumber(to)
    }

    fun confirmHaptic(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun rejectHaptic(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}
