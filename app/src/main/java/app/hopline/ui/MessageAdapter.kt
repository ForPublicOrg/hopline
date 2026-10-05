package app.hopline.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
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
 * The chat list: date chips, an unread divider, coloured sender names, tight runs of bubbles from
 * the same person, time + ticks inside the bubble, reactions hanging off the bottom edge, and
 * photo/file bubbles that fill in as their pieces hop closer.
 *
 * Gestures follow every chat app: tap (my own message → delivery details), double-tap (❤️),
 * long-press anywhere on a message — its photo, file, quote or link included — for the menu.
 *
 * [leftAt] above 0 is the kept chat of a group this phone left, and when it left. Nothing in it
 * is still on its way, so nothing is drawn as if it were: a message that never went out reads
 * "Not sent" whatever its age, and a file that isn't on the phone says so instead of counting
 * pieces that will never come.
 */
class MessageAdapter(
    private val ctx: Context,
    private val router: Router,
    private val showNames: Boolean,
    private val listener: Listener,
    private val leftAt: Long = 0,
) : ListAdapter<MessageAdapter.Row, RecyclerView.ViewHolder>(DIFF) {
    private val readOnly: Boolean get() = leftAt > 0

    interface Listener {
        fun onTap(m: Message)
        fun onLongPress(m: Message, row: View, bubble: View)
        fun onDoubleTap(m: Message, bubble: View)
        fun onAttachment(m: Message)
        fun onLocation(m: Message)
        fun onQuote(m: Message)
        fun onReactions(m: Message)
    }

    /** Messages that arrived on this phone between [since] and [until] (its own clock) are unread. */
    class Unread(val since: Long, val until: Long) {
        fun counts(m: Message, me: String): Boolean =
            m.from != me && !m.isNotice && m.arrivedAt > since && m.arrivedAt <= until
    }

    /** For the swipe-to-reply gesture: which message lives at a list position (null for chips). */
    fun messageAt(pos: Int): Message? = (currentList.getOrNull(pos) as? Row.Msg)?.m

    /** For tapping a quote: where its original sits right now (-1 if not in this list). */
    fun positionOf(id: String): Int = currentList.indexOfFirst { it.key == id }

    fun unreadPosition(): Int = positionOf(UNREAD_KEY)

    class Tick(val text: String, val colorRes: Int, val descRes: Int)
    class QuoteLine(val who: String, val text: String, val accentKey: String)

    sealed class Row(val key: String, val stamp: String) {
        class Chip(key: String, val label: String, val style: Int) : Row(key, "$style|$label")
        class Msg(val m: Message, val name: String?, val grouped: Boolean, val tick: Tick?, val quote: QuoteLine?, extra: String) :
            Row(m.id, "${m.text}|$name|$grouped|${tick?.text}|${tick?.colorRes}|${quote?.who}|${quote?.text}|$extra")
    }

    // ------------------------------------------------------------------ building rows

    /** Per adapter, not static: a language change recreates the screen and must reach the clock too. */
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    /** Files known to be on disk — a file never un-arrives, so it is checked once, not every redraw. */
    private val readyFids = HashSet<String>()
    /** Each message's reactions at the previous submit, to tell a real change from a rebind. */
    private var reactionSeen = HashMap<String, String>()
    private var primed = false
    /** Messages whose reaction pill just changed: id -> when. Their next bind pops the pill. */
    private val pops = HashMap<String, Long>()
    /** Names a mentioned person has gone by (now and in their stored messages), for highlighting. */
    private var mentionNames: Map<String, Set<String>> = emptyMap()

    /** [onCommit] runs after the diffed rows are actually applied — submitList diffs on a
     *  background thread, so a scroll issued right after submit() would land on the old list.
     *  [top]: the line above everything (how the chat is sealed, ChatRules.chip) — never a message. */
    fun submit(messages: List<Message>, unread: Unread?, top: String? = null, onCommit: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val me = router.me.id
        val rows = ArrayList<Row>(messages.size + 8)
        // My own position, folded into location rows' diff stamp: walk ~100 m and the
        // "1.2 km away" lines redraw on the next refresh.
        val fix = myFix()
        val fixStamp = if (fix == null) "-" else "${Math.round(fix.latitude * 1000)},${Math.round(fix.longitude * 1000)}"
        // "Everyone has it" means everyone heard from lately, not every id this phone ever met.
        val active = router.activePeopleList().mapTo(HashSet()) { it.id }
        mentionNames = if (messages.any { it.mentions.isNotEmpty() }) mentionCandidates(messages) else emptyMap()
        val names = HashMap<String, String>()
        fun nameFor(id: String, fallback: String) = names.getOrPut(id) { Ui.uniqueName(router, id, fallback) }
        val days = DayLabels(ctx, now)

        if (top != null) rows.add(Row.Chip(TOP_KEY, top, CHIP_SECURE))
        val unreadCount = if (unread == null) 0 else messages.count { unread.counts(it, me) }
        var dividerDue = unreadCount > 0
        var lastDay = -1
        var prev: Message? = null
        val seen = HashMap<String, String>(messages.size)
        for (m in messages) {
            val day = days.key(m.ts)
            if (day != lastDay) { rows.add(Row.Chip("day-$day", days.label(m.ts), CHIP_DAY)); lastDay = day; prev = null }
            if (dividerDue && unread!!.counts(m, me)) {
                rows.add(Row.Chip(UNREAD_KEY, ctx.resources.getQuantityString(R.plurals.chat_unread_divider, unreadCount, unreadCount), CHIP_UNREAD))
                dividerDue = false; prev = null
            }
            if (m.isNotice) {
                // Only a rename is worded "… renamed the group to …". This phone's own "left" /
                // "rejoined" notes carry no name, and must never be dressed up as a rename.
                val label = when {
                    m.kind == Message.LEFT -> ctx.getString(R.string.chat_notice_left)
                    m.kind == Message.REJOINED -> ctx.getString(R.string.chat_notice_rejoined)
                    m.from == me -> ctx.getString(R.string.chat_notice_renamed_you, m.text)
                    else -> ctx.getString(R.string.chat_notice_renamed, Ui.nameOf(router, m.from, m.fromName), m.text)
                }
                rows.add(Row.Chip(m.id, label, CHIP_NOTICE)); prev = null
                continue
            }
            val mine = m.from == me
            val system = m.kind == Message.SYSTEM
            val p = prev
            val sameRun = p != null && p.from == m.from && p.isPersonal && m.isPersonal && m.ts - p.ts < 4 * 60_000
            val name = when {
                system -> if (mine) ctx.getString(R.string.chat_system_via_you) else ctx.getString(R.string.chat_system_via, Ui.nameOf(router, m.from, m.fromName))
                showNames && !mine && !sameRun -> nameFor(m.from, m.fromName)
                else -> null
            }
            val reactions = reactionStamp(m)
            seen[m.id] = reactions
            if (primed && reactions.isNotEmpty() && reactionSeen[m.id]?.let { it != reactions } == true) pops[m.id] = now
            val extra = (if (m.loc != null) "L$fixStamp" else attState(m, now)) + "|" + reactions + "|" + voiceStamp(m) + "|" + mentionStamp(m)
            rows.add(Row.Msg(m, name, sameRun, tickFor(m, mine, active, now), quoteFor(m, ::nameFor), extra))
            prev = m
        }
        reactionSeen = seen
        primed = true
        pops.entries.removeAll { now - it.value > POP_WINDOW_MS }
        submitList(rows) { onCommit?.invoke() }
    }

    private fun reactionStamp(m: Message): String {
        if (!m.isPersonal || m.reactions.isEmpty()) return ""
        return m.reactionSummary() + (m.reactions[router.me.id]?.let { "|me:$it" } ?: "")
    }

    /** Play state folded into the diff stamp, so only the playing bubble rebinds each tick. */
    private fun voiceStamp(m: Message): String {
        val att = m.att ?: return ""
        if (!att.isAudio) return ""
        return if (VoicePlayer.playingFid == att.fid) "p${VoicePlayer.positionMs(att.fid) / 300}" else ""
    }

    /** Mention highlights follow the names they resolve to — fold them in so a rename redraws. */
    private fun mentionStamp(m: Message): String =
        if (m.mentions.isEmpty()) "" else m.mentions.joinToString(",") { mentionNames[it]?.joinToString("/") ?: "" }

    /** Each mentioned person's current name plus every name their stored messages carry, so an
     *  "@Alice" written before she became "Alicia" still lights up. */
    private fun mentionCandidates(messages: List<Message>): Map<String, Set<String>> {
        val out = HashMap<String, LinkedHashSet<String>>()
        for (m in messages) for (id in m.mentions) out.getOrPut(id) { LinkedHashSet() }
        for ((id, set) in out) {
            val now = if (id == router.me.id) router.me.name else router.people[id]?.name.orEmpty()
            if (now.isNotEmpty()) set.add(now)
        }
        for (m in router.messages) if (m.fromName.isNotEmpty()) out[m.from]?.add(m.fromName)
        return out
    }

    private fun myFix(): android.location.Location? {
        val l = Locations.lastKnown(Core.app) ?: return null
        return if (System.currentTimeMillis() - l.time < FIX_MAX_AGE) l else null
    }

    private fun isReady(att: Attachment): Boolean {
        if (att.fid in readyFids) return true
        if (!Blobs.fileFor(ctx, router.group.fingerprint, att).exists()) return false
        readyFids.add(att.fid)
        return true
    }

    /** Part of the diff stamp: redraws the bubble when more pieces arrive, the file lands, or it
     *  is clear it never will. */
    private fun attState(m: Message, now: Long): String {
        val att = m.att ?: return ""
        if (isReady(att)) return "ready"
        if (att.failed) return "bad"
        if (readOnly || neverArrives(m, now) || ChatRules.lostInUpdate(att)) return "x"
        // A waiting bubble says when the phone is too full to take pieces: that redraws it too.
        return "${router.fileProgress(att)}/${att.chunks}${if (Blobs.storageLow) "!" else ""}"
    }

    private fun tickFor(m: Message, mine: Boolean, active: Set<String>, now: Long): Tick? {
        if (!mine || !m.isPersonal) return null
        // In a kept chat "it never went out" is certain. Whether a private message that did go out
        // was read is something this phone stopped hearing about when it left — it stays ✓, unless
        // its time to be confirmed had already run out by then: that one was "Not delivered"
        // before the leaving, and leaving doesn't turn it back into a tick.
        val stopped = if (readOnly) m.status == Message.QUEUED || (m.to != null && m.status != Message.DELIVERED && Ui.settledBeforeLeaving(m, leftAt))
            else Ui.gaveUp(router, m, now)
        return when {
            stopped ->
                if (m.status == Message.QUEUED) Tick(ctx.getString(R.string.chat_tick_not_sent), R.color.chat_tick_failed, R.string.chat_tick_not_sent_desc)
                else Tick(ctx.getString(R.string.chat_tick_not_delivered), R.color.chat_tick_failed, R.string.chat_tick_not_delivered_desc)
            m.status == Message.QUEUED -> Tick("◷", R.color.tick, R.string.chat_tick_queued_desc)
            m.to != null && m.status == Message.DELIVERED -> Tick("✓✓", R.color.tick_delivered, R.string.chat_tick_delivered_desc)
            // From before the update: confirmed under ids nobody has now, so never "everyone" nor "some" of today's group.
            m.to == null && Ui.beforeUpdate(router, m) ->
                if (m.reached.isEmpty()) Tick("✓", R.color.tick, R.string.chat_tick_sent_desc)
                else Tick("✓✓", R.color.tick, R.string.chat_tick_confirmed_desc)
            m.to == null && active.isNotEmpty() && m.reached.containsAll(active) -> Tick("✓✓", R.color.tick_delivered, R.string.chat_tick_all_desc)
            m.reached.isNotEmpty() -> Tick("✓✓", R.color.tick, R.string.chat_tick_some_desc)
            else -> Tick("✓", R.color.tick, R.string.chat_tick_sent_desc)
        }
    }

    /**
     * The quote block's words. The snippet travelled with the reply; when the original is on this
     * phone its own words are the truth — a doctored snippet must not out-shout a message we can
     * read. Who wrote it is decided by id, never by name: two "Rahul"s, or me after a rename.
     */
    private fun quoteFor(m: Message, nameFor: (String, String) -> String): QuoteLine? {
        if (!m.isPersonal) return null
        val travelled = m.quote ?: return null
        val orig = router.message(travelled.id)
        val q = orig?.let { Quote.of(it, Ui.nameOf(router, it.from, it.fromName)) } ?: travelled
        val author = orig?.from ?: travelled.origin.ifEmpty { null }
        val isMe = if (author != null) author == router.me.id else q.name.isNotEmpty() && q.name == router.me.name
        val who = when {
            isMe -> ctx.getString(R.string.reply_you)
            author != null -> nameFor(author, q.name)
            else -> q.name.ifEmpty { ctx.getString(R.string.someone) }
        }
        return QuoteLine(who, q.text.ifEmpty { "…" }, author ?: q.name.ifEmpty { q.id })
    }

    // ------------------------------------------------------------------ recycler plumbing

    class ChipVH(val b: ItemChipBinding) : RecyclerView.ViewHolder(b.root)

    inner class MsgVH(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {
        var m: Message? = null
        /** Link actions offered to TalkBack on the bubble; replaced on every bind. */
        val linkActions = ArrayList<Int>()
        private val gestures = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            // Messages that can't take a ❤️ answer taps at once; the rest wait out the double-tap window.
            override fun onSingleTapUp(e: MotionEvent): Boolean { if (m?.isPersonal == false) tap(e); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (m?.isPersonal == true) tap(e); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { m?.let { if (it.isPersonal) listener.onDoubleTap(it, b.bubble) }; return true }
            override fun onLongPress(e: MotionEvent) { b.bubble.performLongClick() }
        }
        val detector = GestureDetector(b.root.context, gestures)

        fun allowDoubleTap(on: Boolean) = detector.setOnDoubleTapListener(if (on) gestures else null)

        private fun tap(e: MotionEvent) {
            val span = linkAt(b.text, e.x - b.text.left, e.y - b.text.top)
            if (span != null) openLink(span) else if (b.bubble.hasOnClickListeners()) b.bubble.performClick()
        }

        fun openLink(span: ClickableSpan) {
            try { span.onClick(b.text) } catch (e: ActivityNotFoundException) {
                Toast.makeText(ctx.applicationContext, R.string.chat_no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }

        fun longPress(): Boolean {
            m?.let { listener.onLongPress(it, itemView, b.bubble) }
            return true
        }

        /** The "this one" flash after a jump. Its own animator on the bubble and its reactions —
         *  never itemView.animate(): the item animator cancels that on any change or move of the
         *  row, a cancelled end action never runs, and the row (and every message it is recycled
         *  into) would stay half-faded. */
        private var flashing: ValueAnimator? = null

        fun flash() {
            stopFlash()
            val ease = AccelerateDecelerateInterpolator()
            val fade = PropertyValuesHolder.ofKeyframe("alpha",
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(FLASH_DIP_MS.toFloat() / FLASH_MS, 0.35f).apply { interpolator = ease },
                Keyframe.ofFloat(1f, 1f).apply { interpolator = ease })
            flashing = ValueAnimator.ofPropertyValuesHolder(fade).apply {
                duration = FLASH_MS
                interpolator = LinearInterpolator()   // the keyframes ease each half on their own
                addUpdateListener { fadeTo(it.animatedValue as Float) }
                // Runs when cancelled too: however the flash stops, the message ends fully drawn.
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { fadeTo(1f); if (flashing === a) flashing = null }
                })
                start()
            }
        }

        fun stopFlash() {
            flashing?.cancel()
            fadeTo(1f)
        }

        private fun fadeTo(alpha: Float) { b.bubble.alpha = alpha; b.reactionsPills.alpha = alpha }
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Chip) 0 else 1

    @SuppressLint("ClickableViewAccessibility")   // taps reach performClick() via the gesture detector
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        if (viewType == 0) return ChipVH(ItemChipBinding.inflate(inf, parent, false))
        val h = MsgVH(ItemMessageBinding.inflate(inf, parent, false))
        val b = h.b
        // The bubble owns its gestures: tap, double-tap, long-press. Children that have their own
        // tap (photo, file, place, quote, play) still hand a long-press to the same menu.
        b.bubble.setOnTouchListener { _, ev -> h.detector.onTouchEvent(ev); true }
        // The empty width beside a bubble belongs to it too (a short message is a small target):
        // the same gestures, with the touch moved into the bubble's own coordinates.
        b.row.setOnTouchListener { _, ev ->
            val inBubble = MotionEvent.obtain(ev)
            inBubble.offsetLocation(-b.bubble.left.toFloat(), -b.bubble.top.toFloat())
            h.detector.onTouchEvent(inBubble)
            inBubble.recycle()
            true
        }
        b.bubble.setOnLongClickListener { h.longPress() }
        for (v in listOf(b.image, b.fileRow, b.locationRow, b.quoteBlock, b.voicePlay, b.reactionsPills)) v.setOnLongClickListener { h.longPress() }
        b.image.setOnClickListener { h.m?.let { listener.onAttachment(it) } }
        b.fileRow.setOnClickListener { h.m?.let { listener.onAttachment(it) } }
        b.locationRow.setOnClickListener { h.m?.let { listener.onLocation(it) } }
        b.quoteBlock.setOnClickListener { h.m?.let { listener.onQuote(it) } }
        b.reactionsPills.setOnClickListener { h.m?.let { listener.onReactions(it) } }
        ViewCompat.replaceAccessibilityAction(b.bubble, AccessibilityActionCompat.ACTION_LONG_CLICK,
            ctx.getString(if (readOnly) R.string.chat_a11y_options_left else R.string.chat_a11y_options), null)
        ViewCompat.replaceAccessibilityAction(b.quoteBlock, AccessibilityActionCompat.ACTION_CLICK, ctx.getString(R.string.chat_a11y_show_original), null)
        return h
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (h is ChipVH) { bindChip(h, row as Row.Chip); return }
        h as MsgVH; row as Row.Msg
        val m = row.m; val b = h.b
        // A flash belongs to its message: an in-place update lets it finish, another message never inherits it.
        if (h.m?.id != m.id) h.stopFlash()
        h.m = m
        val mine = m.from == router.me.id
        val system = m.kind == Message.SYSTEM

        b.text.movementMethod = null   // links are handled by the bubble's tap, never by a sticky LinkMovementMethod
        b.text.text = styledText(m, mine)
        // A location message's text is only the maps-link fallback for old clients — the card says it better.
        b.text.isVisible = m.loc == null && (m.text.isNotEmpty() || m.att == null)
        b.time.text = timeFmt.format(Date(m.ts))
        val tick = row.tick
        b.ticks.isVisible = tick != null
        b.ticks.text = tick?.text ?: ""
        b.ticks.contentDescription = tick?.let { ctx.getString(it.descRes) }

        val lp = b.bubble.layoutParams as LinearLayout.LayoutParams
        when {
            system -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_system); lp.gravity = Gravity.CENTER_HORIZONTAL
                b.name.isVisible = true; b.name.text = row.name
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
                tick?.let { b.ticks.setTextColor(ctx.getColor(it.colorRes)) }
            }
            else -> {
                b.bubble.setBackgroundResource(if (row.grouped) R.drawable.bg_bubble_in_grouped else R.drawable.bg_bubble_in)
                lp.gravity = Gravity.START
                b.name.isVisible = row.name != null
                if (row.name != null) { b.name.text = row.name; b.name.setTextColor(nameColor(ctx, m.from)) }
                b.text.setTextColor(ctx.getColor(R.color.bubble_in_text))
                b.text.setLinkTextColor(ctx.getColor(R.color.ember))
                b.time.setTextColor(ctx.getColor(R.color.bubble_in_meta))
            }
        }
        b.bubble.layoutParams = lp
        bindQuote(b, row.quote, mine)
        bindAttachment(b, m, mine)
        bindLocation(b, m, mine)
        bindReactions(b, m, mine, pops.remove(m.id)?.let { System.currentTimeMillis() - it < POP_WINDOW_MS } == true)
        val density = ctx.resources.displayMetrics.density
        b.row.setPaddingRelative(b.row.paddingStart, ((if (row.grouped) 1 else 3) * density).toInt(), b.row.paddingEnd, (1 * density).toInt())

        // Tap means something only on my own message (delivery details). Without a listener the
        // bubble isn't announced as tappable — TalkBack must not offer an action that does nothing.
        if (mine && m.isPersonal) {
            b.bubble.setOnClickListener { listener.onTap(m) }
            ViewCompat.replaceAccessibilityAction(b.bubble, AccessibilityActionCompat.ACTION_CLICK, ctx.getString(R.string.message_details), null)
        } else {
            b.bubble.setOnClickListener(null); b.bubble.isClickable = false
            ViewCompat.replaceAccessibilityAction(b.bubble, AccessibilityActionCompat.ACTION_CLICK, null, null)
        }
        h.allowDoubleTap(m.isPersonal)
        bindLinkActions(h)
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        (h as? MsgVH)?.stopFlash()
    }

    private fun bindChip(h: ChipVH, row: Row.Chip) {
        val c = h.b.chip
        c.text = row.label
        if (row.style == CHIP_UNREAD) {
            c.setBackgroundResource(R.drawable.bg_chat_unread_chip)
            c.setTextColor(ctx.getColor(R.color.ember_deep))
            c.setTypeface(null, Typeface.BOLD)
        } else {
            c.setBackgroundResource(R.drawable.bg_chip)
            c.setTextColor(ctx.getColor(R.color.chip_text))
            c.setTypeface(null, Typeface.NORMAL)
        }
        // The line about how the chat is sealed carries a padlock; no other chip does.
        c.setCompoundDrawablesRelativeWithIntrinsicBounds(if (row.style == CHIP_SECURE) R.drawable.ic_lock_small else 0, 0, 0, 0)
        c.compoundDrawablePadding = if (row.style == CHIP_SECURE) (6 * ctx.resources.displayMetrics.density).toInt() else 0
        // Day chips are headings, so TalkBack can skim the chat a day at a time.
        ViewCompat.setAccessibilityHeading(c, row.style == CHIP_DAY)
    }

    /** Links in the text can't be touched by TalkBack once the bubble owns the taps — offer them as actions. */
    private fun bindLinkActions(h: MsgVH) {
        for (id in h.linkActions) ViewCompat.removeAccessibilityAction(h.b.bubble, id)
        h.linkActions.clear()
        val spanned = h.b.text.text as? Spanned ?: return
        if (!h.b.text.isVisible) return
        for (span in spanned.getSpans(0, spanned.length, URLSpan::class.java).take(3)) {
            val label = ctx.getString(R.string.chat_a11y_open_link, span.url.removePrefix("tel:").removePrefix("mailto:"))
            h.linkActions.add(ViewCompat.addAccessibilityAction(h.b.bubble, label) { _, _ -> h.openLink(span); true })
        }
    }

    /** @Name runs get bold and coloured, so a call-out is visible at a glance; links are underlined. */
    private fun styledText(m: Message, mine: Boolean): CharSequence {
        if (m.text.isEmpty() || m.loc != null) return m.text
        val sp = SpannableString(m.text)
        var styled = Linkify.addLinks(sp, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
        if (m.mentions.isNotEmpty()) {
            // Case-fold for matching; if folding shifts lengths (rare scripts), match exactly instead.
            // (Judged by length, never identity — lowercase() returns the SAME instance for text
            // that is already lowercase, which is most messages.)
            val folded = m.text.lowercase(Locale.ROOT)
            val exact = folded.length != m.text.length
            val hay = if (exact) m.text else folded
            val needles = m.mentions.flatMap { mentionNames[it].orEmpty() }.distinct()
                .map { "@" + if (exact) it else it.lowercase(Locale.ROOT) }.sortedByDescending { it.length }
            val color = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.ember)
            val claimed = ArrayList<IntRange>()
            for (needle in needles) {
                var i = hay.indexOf(needle)
                while (i >= 0) {
                    val end = i + needle.length
                    val startsWord = i == 0 || m.text[i - 1].isWhitespace()   // an email's @ is not a call-out
                    val endsWord = end >= m.text.length || !m.text[end].isLetterOrDigit()
                    // "@Ravi Kumar" claims its characters first; plain "Ravi" can't re-style inside it.
                    if (startsWord && endsWord && claimed.none { i in it || (end - 1) in it }) {
                        claimed.add(i until end)
                        sp.setSpan(StyleSpan(Typeface.BOLD), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sp.setSpan(ForegroundColorSpan(color), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        styled = true
                    }
                    i = hay.indexOf(needle, end)
                }
            }
        }
        return if (styled) sp else m.text
    }

    private fun bindQuote(b: ItemMessageBinding, q: QuoteLine?, mine: Boolean) {
        if (q == null) { b.quoteBlock.isVisible = false; return }
        b.quoteBlock.isVisible = true
        b.quoteBlock.background.mutate().setTint(ctx.getColor(if (mine) R.color.quote_bg_out else R.color.quote_bg_in))
        b.quoteName.text = q.who
        val accent = nameColor(ctx, q.accentKey)
        b.quoteBar.setBackgroundColor(if (mine) ctx.getColor(R.color.bubble_out_text) else accent)
        b.quoteName.setTextColor(if (mine) ctx.getColor(R.color.bubble_out_text) else accent)
        b.quoteText.text = q.text
        b.quoteText.setTextColor(ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta))
    }

    private fun bindReactions(b: ItemMessageBinding, m: Message, mine: Boolean, pop: Boolean) {
        val pill = b.reactionsPills
        pill.animate().cancel()
        pill.scaleX = 1f; pill.scaleY = 1f
        if (!m.isPersonal || m.reactions.isEmpty()) { pill.isVisible = false; return }
        pill.isVisible = true
        pill.text = m.reactionPill()
        val myEmoji = m.reactions[router.me.id]
        pill.setBackgroundResource(if (myEmoji != null) R.drawable.bg_reaction_pill_mine else R.drawable.bg_reaction_pill)
        val lp = pill.layoutParams as LinearLayout.LayoutParams
        lp.gravity = if (mine) Gravity.END else Gravity.START
        pill.layoutParams = lp
        val counts = m.reactionCounts().joinToString(", ") { (e, n) -> "$e $n" }
        pill.contentDescription = ctx.getString(R.string.chat_reactions_desc, counts) +
            (myEmoji?.let { " " + ctx.getString(R.string.chat_reactions_desc_mine, it) } ?: "") +
            " " + ctx.getString(R.string.chat_reactions_desc_hint)
        if (pop) {
            // It just appeared or changed: a small spring, like every chat app's reaction landing.
            pill.scaleX = 0.55f; pill.scaleY = 0.55f
            pill.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3.2f)).start()
        }
    }

    private fun bindLocation(b: ItemMessageBinding, m: Message, mine: Boolean) {
        val loc = m.loc
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
    }

    private fun bindAttachment(b: ItemMessageBinding, m: Message, mine: Boolean) {
        val att = m.att
        if (att == null) {
            b.imageWrap.isVisible = false; b.fileRow.isVisible = false; b.voiceRow.isVisible = false
            b.image.tag = null
            return
        }
        val now = System.currentTimeMillis()
        val ready = isReady(att)
        // Every piece came, but the file didn't check out (Core marked it, and never tries it again):
        // said as it is, never as pieces still on their way.
        val bad = !ready && att.failed
        val gone = !ready && (readOnly || neverArrives(m, now) || ChatRules.lostInUpdate(att))
        val got = if (ready) att.chunks else router.fileProgress(att)
        val waiting = when {
            bad -> ctx.getString(R.string.chat_file_bad)
            // The pieces went when the group was left: said at once, not after "Receiving 0 of N" for two days.
            readOnly -> ctx.getString(R.string.chat_file_left)
            // Still arriving when Hopline was updated: its pieces went with the old format. Said at once too.
            gone && !mine && ChatRules.lostInUpdate(att) -> ctx.getString(R.string.chat_file_before_update)
            gone -> ctx.getString(R.string.chat_file_expired)
            // Pieces are being turned away to keep the phone usable: say why it's stuck.
            Blobs.storageLow -> ctx.getString(R.string.chat_storage_full)
            else -> ctx.getString(R.string.receiving_file, got, att.chunks)
        }
        val fg = ctx.getColor(if (mine) R.color.bubble_out_text else R.color.bubble_in_text)
        val fgMuted = ctx.getColor(if (mine) R.color.bubble_out_meta else R.color.bubble_in_meta)

        // Only our own recordings (they always carry a length) get the voice bubble; a picked
        // .mp3 keeps its name and opens in a real player like any other file.
        if (att.isAudio && att.dur > 0) {
            b.imageWrap.isVisible = false; b.image.tag = null
            b.fileRow.isVisible = false
            b.voiceRow.isVisible = true
            val playing = ready && VoicePlayer.playingFid == att.fid
            b.voicePlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            b.voicePlay.setColorFilter(fg)
            b.voiceInfo.setTextColor(fgMuted)
            b.voiceProgress.progressTintList = ColorStateList.valueOf(fg)
            b.voiceProgress.progressBackgroundTintList = ColorStateList.valueOf(fgMuted)
            if (ready) {
                val file = Blobs.fileFor(ctx, router.group.fingerprint, att)
                val durMs = att.dur * 1000
                val pos = VoicePlayer.positionMs(att.fid)
                b.voiceProgress.progress = if (playing && durMs > 0) (pos * 1000L / durMs).toInt().coerceIn(0, 1000) else 0
                b.voiceInfo.text = if (playing) ctx.getString(R.string.chat_voice_position, clock(pos / 1000), clock(att.dur)) else clock(att.dur)
                b.voicePlay.alpha = 1f
                b.voicePlay.setOnClickListener { VoicePlayer.toggle(file, att.fid) }
                b.voicePlay.contentDescription = if (playing) ctx.getString(R.string.chat_voice_pause_desc)
                                                 else ctx.getString(R.string.chat_voice_play_desc, clock(att.dur))
            } else {
                // The bar honestly shows how much of the clip has hopped in so far (nothing, for one that can't be opened).
                b.voiceProgress.progress = if (att.chunks > 0 && !bad) (got * 1000 / att.chunks).coerceIn(0, 1000) else 0
                b.voiceInfo.text = ctx.getString(R.string.chat_with_detail, clock(att.dur), waiting)
                b.voicePlay.alpha = 0.4f
                b.voicePlay.setOnClickListener(null)
                b.voicePlay.isClickable = false
                b.voicePlay.contentDescription = ctx.getString(R.string.chat_voice_waiting_desc, waiting)
            }
            return
        }
        b.voiceRow.isVisible = false

        if (att.isInlineImage) {
            b.fileRow.isVisible = false
            b.imageWrap.isVisible = true
            // Size the frame from the photo's real shape so the list doesn't jump when it loads.
            val density = ctx.resources.displayMetrics.density
            val w = (240 * density).toInt()
            val ratio = if (att.width > 0 && att.height > 0) att.height.toFloat() / att.width else 0.75f
            val hPx = (w * ratio).toInt().coerceIn((120 * density).toInt(), (340 * density).toInt())
            b.image.layoutParams = b.image.layoutParams.apply { width = w; height = hPx }
            // This photo's own thumbnail first, always: a recycled bubble must never show (or keep,
            // if a decode fails) another message's picture.
            val thumb = Images.thumb(att.thumb)
            if (ready) {
                b.imageProgress.isVisible = false
                // The thumbnail is load()'s placeholder: it stays up while the photo decodes (and if
                // it can't), instead of load() blanking the frame on a cache miss.
                Images.load(Blobs.fileFor(ctx, router.group.fingerprint, att), b.image, placeholder = thumb)
                b.image.contentDescription = ctx.getString(R.string.chat_photo_desc)
            } else {
                b.image.tag = null   // a decode still running for this holder's previous photo must not land here
                if (thumb != null) b.image.setImageBitmap(thumb) else b.image.setImageDrawable(null)
                b.imageProgress.isVisible = true
                b.imageProgress.text = waiting
                b.image.contentDescription = ctx.getString(R.string.chat_photo_waiting_desc, waiting)
            }
        } else {
            b.imageWrap.isVisible = false; b.image.tag = null
            b.fileRow.isVisible = true
            // The sender's name for it, minus anything that would make it read as something else.
            b.fileName.text = MediaRules.shownName(att.name)
            // An app installer is never opened from here (MediaRules): the row says how to keep it instead.
            val openHint = ctx.getString(if (MediaRules.saveOnly(att.name, att.mime)) R.string.media_app_file_hint else R.string.tap_to_open)
            b.fileInfo.text = ctx.getString(R.string.chat_with_detail, Blobs.prettySize(att.size), if (ready) openHint else waiting)
            b.fileName.setTextColor(fg)
            b.fileInfo.setTextColor(fgMuted)
            b.fileIcon.setImageResource(if (bad) R.drawable.ic_media_broken else R.drawable.ic_file)
            b.fileIcon.setColorFilter(fg)
        }
    }

    companion object {
        const val UNREAD_KEY = "unread"
        /** The line at the very top of a chat: how it is sealed. */
        const val TOP_KEY = "top"
        const val CHIP_DAY = 0
        const val CHIP_UNREAD = 1
        const val CHIP_NOTICE = 2
        const val CHIP_SECURE = 3
        private const val POP_WINDOW_MS = 2_000L
        /** The jump flash: fade to 35 % over the first [FLASH_DIP_MS], then back. */
        private const val FLASH_MS = 580L
        private const val FLASH_DIP_MS = 160L
        /** How stale my own fix may be and still power "how far" lines. */
        private const val FIX_MAX_AGE = 15 * 60_000L

        /** 83 seconds -> "1:23". */
        fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

        /**
         * A file whose pieces stopped coming for good: its 48 h of carrying is over, so no phone
         * will offer the rest. Judged from the later of the sender's stamp and our own arrival
         * time, so a slow clock can never make us call a file lost while it may still come.
         */
        fun neverArrives(m: Message, now: Long): Boolean = now - maxOf(m.ts, m.arrivedAt) > Router.CARRY_MS

        /** The tappable link under a point in [tv] (its own coordinates), if any. */
        fun linkAt(tv: TextView, x: Float, y: Float): ClickableSpan? {
            if (!tv.isVisible || x < 0 || y < 0 || x > tv.width || y > tv.height) return null
            val text = tv.text as? Spanned ?: return null
            val layout = tv.layout ?: return null
            val lx = x - tv.totalPaddingLeft + tv.scrollX
            val ly = (y - tv.totalPaddingTop + tv.scrollY).toInt()
            if (ly < 0 || ly > layout.height) return null
            val line = layout.getLineForVertical(ly)
            if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
            val off = layout.getOffsetForHorizontal(line, lx)
            return text.getSpans(off, off, ClickableSpan::class.java).firstOrNull()
        }

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

        fun nameColor(ctx: Context, id: String): Int = ctx.getColor(NAME_COLOR_RES[slot(id)])

        fun avatarColor(id: String): Int {
            val c = AVATAR_COLORS[slot(id)]
            // slightly deepened for white text on a circle
            val r = (Color.red(c) * 0.9).toInt(); val g = (Color.green(c) * 0.9).toInt(); val bl = (Color.blue(c) * 0.9).toInt()
            return Color.rgb(r, g, bl)
        }

        /** "Today", "Yesterday", "12 August" (with the year once it isn't this one). */
        fun dayLabel(ctx: Context, ts: Long): String = DayLabels(ctx, System.currentTimeMillis()).label(ts)

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.stamp == b.stamp
        }
    }

    /** Day keys and labels for one pass over the list — no calendar or formatter per message. */
    private class DayLabels(private val ctx: Context, now: Long) {
        private val cal = Calendar.getInstance()
        private val today: Int
        private val yesterday: Int
        private val thisYear: Int
        private val fmt = SimpleDateFormat("d MMMM", Locale.getDefault())
        private val fmtYear = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        init {
            cal.timeInMillis = now
            thisYear = cal.get(Calendar.YEAR)
            today = key(now)
            cal.timeInMillis = now; cal.add(Calendar.DAY_OF_YEAR, -1)
            yesterday = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }
        fun key(ts: Long): Int { cal.timeInMillis = ts; return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }
        fun label(ts: Long): String = when (key(ts)) {
            today -> ctx.getString(R.string.chat_today)
            yesterday -> ctx.getString(R.string.chat_yesterday)
            else -> { cal.timeInMillis = ts; (if (cal.get(Calendar.YEAR) == thisYear) fmt else fmtYear).format(Date(ts)) }
        }
    }
}
