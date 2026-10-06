package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.SafeUrl
import app.hopline.core.WebText
import app.hopline.databinding.ActivityReaderBinding
import app.hopline.databinding.ItemLinkBinding
import app.hopline.databinding.ItemResultBinding
import app.hopline.mesh.Errand
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
import app.hopline.service.Errands
import app.hopline.service.Notifications
import app.hopline.service.Permissions
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject

/**
 * An answer from the group's shared internet, full screen: a page as readable text with its
 * links one tap from being fetched next, search results each with a Read button, a forecast, or
 * "sent". Everything is read from the router by request id (extra [EXTRA_ERRAND]), so rotation
 * and process death cost nothing. Older-version (public) answers show their plain text.
 */
class ReaderActivity : AppCompatActivity() {
    private lateinit var b: ActivityReaderBinding
    private var ready = false
    private var eid = ""
    /** What is on screen — the page is only rebuilt when the answer itself changed. */
    private var shownKey = ""
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eid = intent.getStringExtra(EXTRA_ERRAND).orEmpty()
        if (eid.isEmpty()) { finish(); return }
        if (!Core.store.hasActive() || !Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        b.toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        // Tappable [n] links inside selectable text.
        b.body.movementMethod = LinkMovementMethod.getInstance()
        ViewCompat.setAccessibilityHeading(b.title, true)
        ViewCompat.setAccessibilityHeading(b.resultsLabel, true)
        ViewCompat.setAccessibilityHeading(b.linksLabel, true)
        b.copy.setOnClickListener { copyAll() }
        b.share.setOnClickListener { confirmShare() }
        ready = true
        Core.version.observe(this) { render() }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (!Core.store.hasActive() || !Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.ensureRunning()
        Core.appVisible = true
        Core.markRead(Core.INTERNET)
        Core.fingerprint()?.let { Notifications.cancelErrand(this, it, eid) }
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
    }

    private fun goBack() {
        if (isTaskRoot) startActivity(Intent(this, InternetActivity::class.java))
        finish()
    }

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------------ drawing

    private fun render() {
        if (!ready || !alive()) return
        val r = Core.router
        if (r == null) {
            b.title.text = getString(R.string.net_status_title_starting)
            b.bar.isVisible = false
            return
        }
        val e = r.errands[eid]
        if (e == null || e.from != r.me.id) {
            // Pruned, or from a group that is no longer on the radio.
            Toast.makeText(this, R.string.net_reader_gone, Toast.LENGTH_LONG).show()
            finish(); return
        }
        val key = "${e.status}|${e.answeredAt}|${e.answerZ.length}|${e.result?.length}"
        if (key == shownKey) return
        shownKey = key
        show(r, e)
    }

    private fun show(r: Router, e: Errand) {
        val a = e.answer()
        b.toolbar.title = NetText.kind(this, e)
        b.title.text = e.title.ifEmpty { Errands.titleFor(e) }
        b.meta.text = meta(r, e, a)

        b.part.isVisible = e.type == Errand.READ && e.parts > 1
        b.part.text = getString(R.string.net_reader_part, e.part, e.parts)

        val notice = when {
            e.status == Errand.FAILED -> NetText.reason(this, e)
            e.isOpen -> NetText.status(this, r, e).ifEmpty { getString(R.string.net_reader_waiting) }
            e.status == Errand.EXPIRED || e.status == Errand.CANCELLED -> NetText.status(this, r, e)
            a == null && e.result != null -> getString(R.string.net_reader_public)
            else -> null
        }
        b.notice.isVisible = notice != null
        b.notice.text = notice

        val results = a?.optJSONArray("r")
        val links = a?.optJSONArray("l")
        val text = when {
            e.status == Errand.FAILED -> ""   // the reason is in the notice; the body would repeat it
            results != null && results.length() > 0 -> a.optString("s")
            a != null -> a.optString("t").ifEmpty { a.optString("d") }
            else -> publicText(e)
        }
        b.body.isVisible = text.isNotBlank()
        b.body.text = styled(text, links)

        renderResults(results)
        renderLinks(if (results != null && results.length() > 0) null else links)

        val src = a?.optString("src").orEmpty()
        b.source.isVisible = src.isNotEmpty()
        b.source.text = when {
            src.startsWith("https://") || src.startsWith("http://") -> getString(R.string.net_reader_source, src)
            e.type == Errand.FIND -> getString(R.string.net_reader_results_from, src)
            else -> src
        }
        renderBar(e, a)
    }

    /** "via Riya's phone · 07:12 · ndtv.com · used 52 KB of their data" */
    private fun meta(r: Router, e: Errand, a: JSONObject?): String {
        val parts = ArrayList<String>()
        val local = e.helper == r.me.id
        if (e.answeredAt > 0) {
            parts += if (local) getString(R.string.net_reader_here) else getString(R.string.net_reader_via, NetText.answeredBy(this, r, e))
            parts += NetText.clock(this, e.answeredAt)
        } else parts += NetText.clock(this, e.ts)
        val src = a?.optString("src").orEmpty().ifEmpty { if (e.type == Errand.READ) e.args.optString("url") else "" }
        if (src.startsWith("http")) parts += WebText.hostOf(src)
        if (e.cost > 0) parts += getString(if (local) R.string.net_reader_cost_mine else R.string.net_reader_cost, Blobs.prettySize(e.cost.toLong()))
        return parts.joinToString(" · ")
    }

    /** An older helper's public answer is "title\ntext"; the title is already the heading. */
    private fun publicText(e: Errand): String {
        val t = e.result.orEmpty()
        return if (e.title.isNotEmpty() && t.startsWith(e.title)) t.removePrefix(e.title).trimStart('\n') else t
    }

    /**
     * Plain text from another phone, made readable: "# " lines become bold headings and "[n]"
     * markers become tappable links to the page's n-th link. Never parsed as HTML.
     */
    private fun styled(text: String, links: JSONArray?): CharSequence {
        val sb = SpannableStringBuilder()
        for ((i, line) in text.lines().withIndex()) {
            if (i > 0) sb.append('\n')
            if (line.startsWith("# ")) {
                val start = sb.length
                sb.append(line.removePrefix("# "))
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.12f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else sb.append(line)
        }
        if (links == null || links.length() == 0) return sb
        val color = getColor(R.color.internet_badge)
        for (m in LINK_MARK.findAll(sb)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val (label, url) = link(links, n - 1) ?: continue
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { askToRead(url, label) }
                override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = false; ds.isFakeBoldText = true }
            }, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** One link of the answer, only if it is a web address (never intent:, javascript:, file:). */
    private fun link(links: JSONArray, i: Int): Pair<String, String>? {
        val l = links.optJSONArray(i) ?: return null
        val url = l.optString(1)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return Names.clean(l.optString(0), 120).ifEmpty { WebText.hostOf(url) } to url
    }

    private fun renderResults(results: JSONArray?) {
        b.results.removeAllViews()
        val n = results?.length() ?: 0
        b.resultsLabel.isVisible = n > 0
        for (i in 0 until n) {
            // [title, url, snippet] (or {t, u, s} from a newer helper)
            val arr = results!!.optJSONArray(i)
            val obj = results.optJSONObject(i)
            val title = Names.clean(arr?.optString(0) ?: obj?.optString("t"), 160)
            val url = arr?.optString(1) ?: obj?.optString("u").orEmpty()
            val snippet = (arr?.optString(2) ?: obj?.optString("s")).orEmpty().take(300)
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            val row = ItemResultBinding.inflate(layoutInflater, b.results, false)
            row.title.text = title.ifEmpty { WebText.hostOf(url) }
            row.host.text = WebText.hostOf(url)
            row.snippet.text = snippet
            row.snippet.isVisible = snippet.isNotBlank()
            row.read.contentDescription = getString(R.string.net_read) + ": " + row.title.text
            row.read.setOnClickListener { askToRead(url, row.title.text.toString()) }
            b.results.addView(row.root)
        }
    }

    private fun renderLinks(links: JSONArray?) {
        b.links.removeAllViews()
        var shown = 0
        for (i in 0 until (links?.length() ?: 0)) {
            val (label, url) = link(links!!, i) ?: continue
            val row = ItemLinkBinding.inflate(layoutInflater, b.links, false)
            row.num.text = getString(R.string.net_link_num, i + 1)
            row.label.text = label
            row.host.text = WebText.hostOf(url)
            row.root.contentDescription = getString(R.string.net_link_desc, i + 1, label)
            row.root.setOnClickListener { askToRead(url, label) }
            b.links.addView(row.root)
            shown++
        }
        b.links.isVisible = shown > 0
        b.linksLabel.isVisible = shown > 0
    }

    private fun renderBar(e: Errand, a: JSONObject?) {
        b.bar.isVisible = true
        val text = shareableText(e, a)
        val hasText = text.isNotBlank() && e.status == Errand.DONE
        b.copy.isVisible = hasText
        val more = e.status == Errand.DONE && e.type == Errand.READ && e.part < e.parts
        val again = !e.isOpen && e.status != Errand.DONE
        // Only admins may send in the group now, and this phone isn't one: Copy stays, sharing to the group goes.
        val canShare = Core.mayPost()
        when {
            more -> {
                b.primary.text = getString(R.string.net_reader_more, e.part + 1, e.parts)
                b.primary.setOnClickListener { readMore(e.id) }
                b.share.isVisible = hasText && canShare
            }
            again -> {
                b.primary.text = getString(R.string.net_ask_again)
                b.primary.setOnClickListener { askAgain(e.id) }
                b.share.isVisible = false
            }
            hasText -> {
                if (canShare) {
                    b.primary.text = getString(R.string.net_reader_share)
                    b.primary.setOnClickListener { confirmShare() }
                }
                b.share.isVisible = false
            }
            else -> b.bar.isVisible = false
        }
        b.primary.isVisible = more || again || (hasText && canShare)
    }

    // ------------------------------------------------------------------ actions

    private fun currentErrand(): Pair<Router, Errand>? {
        val r = Core.router ?: return null
        val e = r.errands[eid] ?: return null
        return r to e
    }

    /** The answer as plain text for copying and sharing: no heading marks, no link numbers. */
    private fun shareableText(e: Errand, a: JSONObject?): String =
        NetText.plain(a?.optString("t") ?: publicText(e)).trim()

    private fun copyAll() {
        val (_, e) = currentErrand() ?: return
        val a = e.answer()
        val src = a?.optString("src").orEmpty().takeIf { it.startsWith("http") }
        val text = listOfNotNull(e.title.ifEmpty { null }, shareableText(e, a), src).joinToString("\n\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(e.title, text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** Posts a normal chat message every version can read — after the person says yes. */
    private fun confirmShare() {
        if (!Core.mayPost()) { Snackbar.make(b.root, R.string.chat_admins_only_not_sent, Snackbar.LENGTH_LONG).show(); return }
        val (r, e) = currentErrand() ?: return
        val message = shareMessage(r, e)
        if (!alive()) return
        val groupName = r.group.name
        dialog = AlertDialog.Builder(this)
            .setTitle(if (groupName.isNotEmpty()) getString(R.string.net_reader_share_title, groupName) else getString(R.string.net_reader_share_group))
            .setMessage(message.take(SHARE_PREVIEW) + if (message.length > SHARE_PREVIEW) "…" else "")
            .setPositiveButton(R.string.net_reader_share) { _, _ ->
                if (Core.router !== r) return@setPositiveButton   // switched groups meanwhile
                // Only admins came to be able to send while the question stood: nothing went.
                if (r.sendChat(message.take(Router.MAX_TEXT)) == null) {
                    Snackbar.make(b.root, R.string.chat_admins_only_not_sent, Snackbar.LENGTH_LONG).show(); return@setPositiveButton
                }
                Core.saveNow()
                Core.changed()
                NetText.confirmHaptic(b.root)
                Snackbar.make(b.root, R.string.net_reader_shared, Snackbar.LENGTH_LONG)
                    .setAction(R.string.net_reader_open_chat) { startActivity(Intent(this, ChatActivity::class.java)) }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** "🌐 <title>\n<text, up to ~1500 chars>\n<link>\n(via Riya's phone)" */
    private fun shareMessage(r: Router, e: Errand): String {
        val a = e.answer()
        val body = shareableText(e, a).let { if (it.length > SHARE_CHARS) it.take(SHARE_CHARS).trimEnd() + "…" else it }
        val src = a?.optString("src").orEmpty().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val via = if (e.helper == r.me.id) null else getString(R.string.net_reader_share_via, NetText.answeredBy(this, r, e))
        return listOfNotNull("🌐 " + e.title.ifEmpty { Errands.titleFor(e) }, body.ifEmpty { null }, src, via)
            .joinToString("\n").take(Router.MAX_TEXT)
    }

    private fun readMore(id: String) {
        val (_, e) = currentErrand() ?: return
        if (e.id != id) return
        val a = e.answer()
        val src = a?.optString("src").orEmpty().takeIf { it.startsWith("http") } ?: e.args.optString("url")
        val url = SafeUrl.fromInput(src) ?: run { snack(getString(R.string.net_bad_link)); return }
        request(Errand.READ, JSONObject().put("url", url).put("part", e.part + 1), prefer = e.helper)
    }

    private fun askAgain(id: String) {
        val (_, e) = currentErrand() ?: return
        if (e.id != id) return
        request(e.type, JSONObject(e.args.toString()))
    }

    /** A link inside the answer: read it next, through whoever has signal — after asking. */
    private fun askToRead(url: String, label: String) {
        val r = Core.router ?: return
        if (!alive()) return
        val target = SafeUrl.fromInput(url) ?: run { snack(getString(R.string.net_bad_link)); return }
        val mine = Core.internetNow()
        val helper = r.capableHelpers(Errand("probe", Errand.READ, JSONObject(), r.me.id, r.me.name, 0)).firstOrNull()
        val message = when {
            mine -> getString(R.string.net_reader_link_mine)
            helper != null -> getString(R.string.net_reader_link_via, Ui.nameOf(r, helper.id, helper.name))
            else -> getString(R.string.net_reader_link_wait)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(label.ifEmpty { getString(R.string.net_reader_link_title) })
            .setMessage(message + "\n\n" + WebText.hostOf(target))
            .setPositiveButton(R.string.net_reader_link_read) { _, _ -> request(Errand.READ, JSONObject().put("url", target)) }
            .setNegativeButton(R.string.cancel, null)
        // With my own signal, the real page is one tap away too.
        if (mine) builder.setNeutralButton(R.string.net_reader_open_browser) { _, _ -> openInBrowser(target) }
        dialog = builder.show()
    }

    private fun openInBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (x: ActivityNotFoundException) { snack(getString(R.string.net_reader_no_browser)) }
    }

    private fun request(type: String, args: JSONObject, prefer: String? = null) {
        if (Core.router == null) { snack(getString(R.string.net_starting)); return }
        Core.requestProblem()?.let { NetText.rejectHaptic(b.root); snack(it); return }
        val e = Core.requestErrand(type, args, prefer) ?: run {
            NetText.rejectHaptic(b.root)
            snack(Core.requestProblem() ?: getString(R.string.net_too_long))
            return
        }
        NetText.confirmHaptic(b.root)
        val fp = Core.fingerprint()
        Snackbar.make(b.root, R.string.net_reader_asked, Snackbar.LENGTH_LONG)
            .setAction(R.string.net_reader_view) {
                // Back to the hub (the existing one if it is underneath), showing the new card.
                startActivity(Intent(this, InternetActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Notifications.EXTRA_ERRAND, e.id)
                    .apply { fp?.let { putExtra(Notifications.EXTRA_FP, it) } })
            }
            .show()
    }

    private fun snack(text: String) {
        if (ready && alive()) Snackbar.make(b.root, text, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_ERRAND = Notifications.EXTRA_ERRAND
        private val LINK_MARK = Regex("""\[(\d{1,3})]""")
        private const val SHARE_CHARS = 1500
        private const val SHARE_PREVIEW = 400
    }
}
