package app.hopline.ui

import android.graphics.Color
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
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
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
) : ListAdapter<MessageAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row(val key: String, val stamp: String) {
        class Chip(key: String, val label: String) : Row(key, label)
        class Msg(val m: Message, val showName: Boolean, val grouped: Boolean, val ticks: String, val blue: Boolean, attState: String) :
            Row(m.id, "${m.text}|$showName|$grouped|$ticks|$blue|$attState")
    }

    fun submit(messages: List<Message>) {
        val rows = ArrayList<Row>(messages.size + 8)
        var lastDay = ""
        var prev: Message? = null
        for (m in messages) {
            val day = dayLabel(m.ts)
            if (day != lastDay) { rows.add(Row.Chip("day-$day", day)); lastDay = day; prev = null }
            val mine = m.from == router.me.id
            val system = m.kind == Message.SYSTEM
            val sameRun = prev != null && prev!!.from == m.from && prev!!.kind != Message.SYSTEM && m.ts - prev!!.ts < 4 * 60_000
            val (ticks, blue) = ticksFor(m, mine, system)
            rows.add(Row.Msg(m, showNames && !mine && !system && !sameRun, sameRun, ticks, blue, attState(m)))
            prev = m
        }
        submitList(rows)
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

        b.text.text = m.text
        b.text.isVisible = m.text.isNotEmpty() || m.att == null
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
        bindAttachment(b, m, mine)
        b.row.setPadding(b.row.paddingLeft, if (row.grouped) 2 else 8, b.row.paddingRight, 2)
        b.bubble.setOnClickListener { onMessageTap(m) }
        b.bubble.setOnLongClickListener { onMessageLong(m); true }
    }

    private fun bindAttachment(b: ItemMessageBinding, m: Message, mine: Boolean) {
        val att = m.att
        val ctx = b.root.context
        if (att == null) { b.imageWrap.isVisible = false; b.fileRow.isVisible = false; return }
        val fp = Core.fingerprint()
        val file = fp?.let { Blobs.fileFor(Core.app, it, att) }
        val ready = file != null && file.exists()
        val got = if (ready) att.chunks else router.fileProgress(att)

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
