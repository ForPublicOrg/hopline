package app.hopline.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ItemChipBinding
import app.hopline.databinding.ItemMessageBinding
import app.hopline.mesh.Attachment
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
import app.hopline.service.Locations
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The chat list: date chips, coloured sender names, tight runs of bubbles from the same person,
 * time + ticks inside the bubble, and photo/file bubbles that fill in as their pieces hop closer.
 */
class MessageAdapter(
    private val router: Router,
    private val showNames: Boolean,
    private val onMessageTap: (Message) -> Unit,
    private val onMessageLong: (Message) -> Unit,
    private val onAttachmentTap: (Message) -> Unit,
    private val onLocationTap: (Message) -> Unit,
    private val onQuoteTap: (Message) -> Unit,
    private val onReactionsTap: (Message) -> Unit,
) : ListAdapter<MessageAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    /** For the swipe-to-reply gesture: which message lives at a list position (null for chips). */
    fun messageAt(pos: Int): Message? = (currentList.getOrNull(pos) as? Row.Msg)?.m

    /** For tapping a quote: where its original sits right now (-1 if not on this phone). */
    fun positionOf(id: String): Int = currentList.indexOfFirst { it.key == id }

    sealed class Row(val key: String, val stamp: String) {
        class Chip(key: String, val label: String) : Row(key, label)
        class Msg(val m: Message, val showName: Boolean, val grouped: Boolean, val ticks: String, val blue: Boolean, attState: String) :
            Row(m.id, "${m.text}|$showName|$grouped|$ticks|$blue|$attState")
    }

    fun submit(messages: List<Message>) {
        val rows = ArrayList<Row>(messages.size + 8)
        var lastDay = ""
        var prev: Message? = null
        // My own position, folded into location rows' diff stamp: walk ~100 m and the
        // "1.2 km away" lines redraw on the next refresh.
        val fix = myFix()
        val fixStamp = if (fix == null) "-" else "${Math.round(fix.latitude * 1000)},${Math.round(fix.longitude * 1000)}"
        for (m in messages) {
            val day = dayLabel(m.ts)
            if (day != lastDay) { rows.add(Row.Chip("day-$day", day)); lastDay = day; prev = null }
            val mine = m.from == router.me.id
            val system = m.kind == Message.SYSTEM
            val sameRun = prev != null && prev!!.from == m.from && prev!!.kind != Message.SYSTEM && m.ts - prev!!.ts < 4 * 60_000
            val (ticks, blue) = ticksFor(m, mine, system)
            // Mention names resolve through presence — fold them in so highlights appear once known.
            val mentionStamp = if (m.mentions.isEmpty()) "" else m.mentions.joinToString(",") { router.people[it]?.name ?: "" }
            // Quotes re-render once the original message hops in (its own words replace the snippet).
            val quoteStamp = m.quote?.let { if (router.message(it.id) != null) "q1" else "q0" } ?: ""
            val extra = (if (m.loc != null) "L$fixStamp" else attState(m)) +
                "|" + m.reactionSummary() + "|" + voiceStamp(m) + "|" + mentionStamp + quoteStamp
            rows.add(Row.Msg(m, showNames && !mine && !system && !sameRun, sameRun, ticks, blue, extra))
            prev = m
        }
        submitList(rows)
    }

    /** Play state folded into the diff stamp, so only the playing bubble rebinds each tick. */
    private fun voiceStamp(m: Message): String {
        val att = m.att ?: return ""
        if (!att.isAudio) return ""
        return if (VoicePlayer.playingFid == att.fid) "p${VoicePlayer.positionMs(att.fid) / 300}" else ""
    }

    private fun myFix(): android.location.Location? {
        val l = Locations.lastKnown(Core.app) ?: return null
        return if (System.currentTimeMillis() - l.time < FIX_MAX_AGE) l else null
    }

    /** Part of the diff stamp: redraws the bubble when more pieces arrive or the file lands. */
    private fun attState(m: Message): String {
        val att = m.att ?: return ""
        val fp = Core.fingerprint() ?: return ""
        val file = Blobs.fileFor(Core.app, fp, att)
        return if (file.exists()) "ready" else "${router.fileProgress(att)}/${att.chunks}"
    }

    private fun ticksFor(m: Message, mine: Boolean, system: Boolean): Pair<String, Boolean> {
        if (!mine || system) return "" to false
        val everyone = router.people.isNotEmpty() && m.reached.size >= router.people.size
        return when {
            m.status == Message.QUEUED -> "◷" to false
            m.to != null && m.status == Message.DELIVERED -> "✓✓" to true
            m.to == null && everyone -> "✓✓" to true
            m.reached.isNotEmpty() -> "✓✓" to false
            else -> "✓" to false
        }
    }

    // ------------------------------------------------------------------ recycler plumbing

    class ChipVH(val b: ItemChipBinding) : RecyclerView.ViewHolder(b.root)
    class MsgVH(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Chip) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) ChipVH(ItemChipBinding.inflate(inf, parent, false))
        else MsgVH(ItemMessageBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (h is ChipVH) { h.b.chip.text = (row as Row.Chip).label; return }
        h as MsgVH; row as Row.Msg
        val m = row.m; val b = h.b
        val ctx = b.root.context
        val mine = m.from == router.me.id
        val system = m.kind == Message.SYSTEM

        b.text.text = styledText(ctx, m, mine)
        // A location message's text is only the maps-link fallback for old clients — the card says it better.
        b.text.isVisible = m.loc == null && (m.text.isNotEmpty() || m.att == null)
        b.time.text = timeFmt.format(Date(m.ts))
        b.ticks.text = row.ticks
        b.ticks.isVisible = row.ticks.isNotEmpty()

        val lp = b.bubble.layoutParams as LinearLayout.LayoutParams
        when {
            system -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_system); lp.gravity = Gravity.CENTER_HORIZONTAL
                b.name.isVisible = true; b.name.text = "🌐 Shared internet — via ${m.fromName}'s phone"
                b.name.setTextColor(ctx.getColor(R.color.bubble_system_text))
                b.text.setTextColor(ctx.getColor(R.color.bubble_system_text))
                b.text.setLinkTextColor(ctx.getColor(R.color.internet_badge))
                b.time.setTextColor(ctx.getColor(R.color.text_faint))
            }
            mine -> {
                b.bubble.setBackgroundResource(if (row.grouped) R.drawable.bg_bubble_out_grouped else R.drawable.bg_bubble_out)
                lp.gravity = Gravity.END; b.name.isVisible = false
                b.text.setTextColor(ctx.getColor(R.color.bubble_out_text))
                b.text.setLinkTextColor(ctx.getColor(R.color.bubble_out_text))
                b.time.setTextColor(ctx.getColor(R.color.bubble_out_meta))
                b.ticks.setTextColor(ctx.getColor(if (row.blue) R.color.tick_delivered else R.color.tick))
            }
            else -> {
                b.bubble.setBackgroundResource(if (row.grouped) R.drawable.bg_bubble_in_grouped else R.drawable.bg_bubble_in)
                lp.gravity = Gravity.START
                b.name.isVisible = row.showName
                if (row.showName) { b.name.text = m.fromName.ifEmpty { "Someone" }; b.name.setTextColor(nameColor(ctx, m.from)) }
                b.text.setTextColor(ctx.getColor(R.color.bubble_in_text))
                b.text.setLinkTextColor(ctx.getColor(R.color.ember))
                b.time.setTextColor(ctx.getColor(R.color.bubble_in_meta))
            }
        }
        b.bubble.layoutParams = lp
        bindQuote(b, m, mine, system)
        bindAttachment(b, m, mine)
        bindLocation(b, m, mine)
        bindReactions(b, m, mine, system)
        b.row.setPadding(b.row.paddingLeft, if (row.grouped) 2 else 8, b.row.paddingRight, 2)
        b.bubble.setOnClickListener { onMessageTap(m) }
        b.bubble.setOnLongClickListener { onMessageLong(m); true }
    }

    /** @Name runs get bold and coloured, so a call-out is visible at a glance. */
    private fun styledText(ctx: android.content.Context, m: Message, mine: Boolean): CharSequence {
        if (m.mentions.isEmpty() || m.text.isEmpty()) return m.text
        val names = ArrayList<String>(m.mentions.size)
        for (id in m.mentions) {
            val n = if (id == router.me.id) router.me.name else router.people[id]?.name ?: ""
            if (n.isNotEmpty()) names.add(n)
        }
        if (names.isEmpty()) return m.text
        // Case-fold for matching; if folding shifts lengths (rare scripts), match exactly instead.
        // (Judged by length, never identity — lowercase() returns the SAME instance for text
        // that is already lowercase, which is most messages.)
        val folded = m.text.lowercase(Locale.ROOT)
        val exact = folded.length != m.text.length
        val lower = if (exact) m.text else folded
        val sp = SpannableString(m.text)
        val color = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.ember)
        for (n in names) {
            val needle = "@" + (if (exact) n else n.lowercase(Locale.ROOT))
            var i = lower.indexOf(needle)
            while (i >= 0) {
                val end = i + needle.length
                val startsWord = i == 0 || m.text[i - 1].isWhitespace()   // an email's @ is not a call-out
                val endsWord = end >= m.text.length || !m.text[end].isLetterOrDigit()
                if (startsWord && endsWord) {
                    sp.setSpan(StyleSpan(Typeface.BOLD), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(ForegroundColorSpan(color), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i = lower.indexOf(needle, end)
            }
        }
        return sp
    }

    private fun bindQuote(b: ItemMessageBinding, m: Message, mine: Boolean, system: Boolean) {
        // The snippet travelled with the reply; when the original is on this phone, its own
        // words are the truth — a doctored snippet must not out-shout a message we can read.
        val q = m.quote?.let { travelled -> router.message(travelled.id)?.let { Quote.of(it) } ?: travelled }
        val ctx = b.root.context
        if (q == null || system) { b.quoteBlock.isVisible = false; return }
        b.quoteBlock.isVisible = true
        b.quoteBlock.background.mutate().setTint(ctx.getColor(if (mine) R.color.quote_bg_out else R.color.quote_bg_in))
        val who = if (q.name == router.me.name && q.name.isNotEmpty()) ctx.getString(R.string.reply_you) else q.name.ifEmpty { "Someone" }
        b.quoteName.text = who
        val accent = nameColor(ctx, q.name.ifEmpty { q.id })
        b.quoteBar.setBackgroundColor(accent)
        b.quoteName.setTextColor(if (mine) ctx.getColor(R.color.bubble_out_text) else accent)
        b.quoteText.text = q.text.ifEmpty { "…" }
        b.quoteText.setTextColor(ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta))
        b.quoteBlock.setOnClickListener { onQuoteTap(m) }
    }

    private fun bindReactions(b: ItemMessageBinding, m: Message, mine: Boolean, system: Boolean) {
        val pills = if (system) "" else m.reactionSummary()
        b.reactionsPills.isVisible = pills.isNotEmpty()
        if (pills.isEmpty()) return
        b.reactionsPills.text = pills
        (b.reactionsPills.layoutParams as LinearLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START
        b.reactionsPills.setOnClickListener { onReactionsTap(m) }
    }

    private fun bindLocation(b: ItemMessageBinding, m: Message, mine: Boolean) {
        val loc = m.loc
        val ctx = b.root.context
        if (loc == null) { b.locationRow.isVisible = false; return }
        b.locationRow.isVisible = true
        b.locationTitle.text = loc.label.ifEmpty { ctx.getString(R.string.attach_location) }
        val sub = StringBuilder(loc.pretty())
        if (loc.acc > 0) sub.append(" · ").append(ctx.getString(R.string.loc_accuracy, Loc.prettyDistance(loc.acc.toDouble())))
        myFix()?.let { fix ->
            val d = Loc.distanceMeters(fix.latitude, fix.longitude, loc.lat, loc.lng)
            if (d >= 25) {
                val dir = Loc.compass(Loc.bearingDeg(fix.latitude, fix.longitude, loc.lat, loc.lng))
                sub.append('\n').append(ctx.getString(R.string.loc_away, Loc.prettyDistance(d), dir))
            } else sub.append('\n').append(ctx.getString(R.string.loc_here))
        }
        b.locationSub.text = sub
        val fg = ctx.getColor(if (mine) R.color.bubble_out_text else R.color.bubble_in_text)
        val fgMuted = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta)
        b.locationTitle.setTextColor(fg)
        b.locationSub.setTextColor(fgMuted)
        b.locationIcon.setColorFilter(fg)
        b.locationRow.setOnClickListener { onLocationTap(m) }
    }

    private fun bindAttachment(b: ItemMessageBinding, m: Message, mine: Boolean) {
        val att = m.att
        val ctx = b.root.context
        if (att == null) { b.imageWrap.isVisible = false; b.fileRow.isVisible = false; b.voiceRow.isVisible = false; return }
        val fp = Core.fingerprint()
        val file = fp?.let { Blobs.fileFor(Core.app, it, att) }
        val ready = file != null && file.exists()
        val got = if (ready) att.chunks else router.fileProgress(att)

        // Only our own recordings (they always carry a length) get the voice bubble; a picked
        // .mp3 keeps its name and opens in a real player like any other file.
        if (att.isAudio && att.dur > 0) {
            b.imageWrap.isVisible = false
            b.fileRow.isVisible = false
            b.voiceRow.isVisible = true
            val fg = ctx.getColor(if (mine) R.color.bubble_out_text else R.color.bubble_in_text)
            val fgMuted = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta)
            val playing = VoicePlayer.playingFid == att.fid
            b.voicePlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            b.voicePlay.setColorFilter(fg)
            b.voiceInfo.setTextColor(fgMuted)
            b.voiceProgress.progressTintList = ColorStateList.valueOf(fg)
            b.voiceProgress.progressBackgroundTintList = ColorStateList.valueOf(fgMuted)
            if (ready) {
                val durMs = att.dur * 1000
                val pos = VoicePlayer.positionMs(att.fid)
                b.voiceProgress.progress = if (playing && durMs > 0) (pos * 1000L / durMs).toInt().coerceIn(0, 1000) else 0
                b.voiceInfo.text = if (playing) "${clock(pos / 1000)} / ${clock(att.dur)}" else clock(att.dur)
                b.voicePlay.alpha = 1f
                b.voicePlay.setOnClickListener { VoicePlayer.toggle(file!!, att.fid) }
            } else {
                // The bar honestly shows how much of the clip has hopped in so far.
                b.voiceProgress.progress = if (att.chunks > 0) (got * 1000 / att.chunks).coerceIn(0, 1000) else 0
                b.voiceInfo.text = "${clock(att.dur)} · ${ctx.getString(R.string.receiving_file, got, att.chunks)}"
                b.voicePlay.alpha = 0.4f
                b.voicePlay.setOnClickListener(null)
            }
            return
        }
        b.voiceRow.isVisible = false

        if (att.isImage) {
            b.fileRow.isVisible = false
            b.imageWrap.isVisible = true
            // Size the frame from the photo's real shape so the list doesn't jump when it loads.
            val density = ctx.resources.displayMetrics.density
            val w = (240 * density).toInt()
            val ratio = if (att.width > 0 && att.height > 0) att.height.toFloat() / att.width else 0.75f
            val hPx = (w * ratio).toInt().coerceIn((120 * density).toInt(), (340 * density).toInt())
            b.image.layoutParams = b.image.layoutParams.apply { width = w; height = hPx }
            if (ready) {
                b.imageProgress.isVisible = false
                Images.load(file!!, b.image)
            } else {
                b.imageProgress.isVisible = true
                b.imageProgress.text = ctx.getString(R.string.receiving_file, got, att.chunks)
                Images.thumb(att.thumb)?.let { b.image.setImageBitmap(it) } ?: b.image.setImageDrawable(null)
            }
            b.image.setOnClickListener { if (ready) onAttachmentTap(m) }
        } else {
            b.imageWrap.isVisible = false
            b.fileRow.isVisible = true
            b.fileName.text = att.name
            b.fileInfo.text = when {
                ready -> "${Blobs.prettySize(att.size)} · ${ctx.getString(R.string.tap_to_open)}"
                else -> "${Blobs.prettySize(att.size)} · ${ctx.getString(R.string.receiving_file, got, att.chunks)}"
            }
            val fg = ctx.getColor(if (mine) R.color.bubble_out_text else R.color.bubble_in_text)
            val fgMuted = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta)
            b.fileName.setTextColor(fg)
            b.fileInfo.setTextColor(fgMuted)
            b.fileIcon.setColorFilter(fg)
            b.fileRow.setOnClickListener { if (ready) onAttachmentTap(m) }
        }
    }

    companion object {
        /** How stale my own fix may be and still power "how far" lines. */
        private const val FIX_MAX_AGE = 15 * 60_000L

        /** 83 seconds -> "1:23". */
        fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        /** Sender-name colours resolve through resources so night mode gets readable twins. */
        private val NAME_COLOR_RES = intArrayOf(
            R.color.name_0, R.color.name_1, R.color.name_2, R.color.name_3,
            R.color.name_4, R.color.name_5, R.color.name_6, R.color.name_7,
        )
        /** Avatar circles always hold white text, so they keep one fixed deep palette. */
        private val AVATAR_COLORS = intArrayOf(
            0xFFC2185B.toInt(), 0xFF7B1FA2.toInt(), 0xFF512DA8.toInt(), 0xFF303F9F.toInt(),
            0xFF1976D2.toInt(), 0xFF00796B.toInt(), 0xFFE64A19.toInt(), 0xFF5D4037.toInt(),
        )

        private fun slot(id: String): Int = (id.hashCode() and 0x7FFFFFFF) % 8

        fun nameColor(ctx: android.content.Context, id: String): Int = ctx.getColor(NAME_COLOR_RES[slot(id)])

        fun avatarColor(id: String): Int {
            val c = AVATAR_COLORS[slot(id)]
            // slightly deepened for white text on a circle
            val r = (Color.red(c) * 0.9).toInt(); val g = (Color.green(c) * 0.9).toInt(); val bl = (Color.blue(c) * 0.9).toInt()
            return Color.rgb(r, g, bl)
        }

        fun dayLabel(ts: Long): String {
            val cal = Calendar.getInstance(); val today = cal.clone() as Calendar
            cal.timeInMillis = ts
            fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val yesterday = (today.clone() as Calendar).also { it.add(Calendar.DAY_OF_YEAR, -1) }
            return when {
                sameDay(cal, today) -> "Today"
                sameDay(cal, yesterday) -> "Yesterday"
                else -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(ts))
            }
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.stamp == b.stamp
        }
    }
}
