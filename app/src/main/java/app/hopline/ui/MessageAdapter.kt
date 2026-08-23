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
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The chat list, styled like the messaging apps everyone already knows: date chips, coloured
 * sender names, tight runs of bubbles from the same person, and time + ticks inside the bubble.
 */
class MessageAdapter(
    private val router: Router,
    private val showNames: Boolean,
    private val onMessageTap: (Message) -> Unit,
) : ListAdapter<MessageAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row(val key: String, val stamp: String) {
        class Chip(key: String, val label: String) : Row(key, label)
        class Msg(val m: Message, val showName: Boolean, val grouped: Boolean, val ticks: String, val blue: Boolean) :
            Row(m.id, "${m.text}|$showName|$grouped|$ticks|$blue")
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
            rows.add(Row.Msg(m, showNames && !mine && !system && !sameRun, sameRun, ticks, blue))
            prev = m
        }
        submitList(rows)
    }

    private fun ticksFor(m: Message, mine: Boolean, system: Boolean): Pair<String, Boolean> {
        if (!mine || system) return "" to false
        val everyone = router.people.isNotEmpty() && m.reached.size >= router.people.size
        return when {
            m.status == Message.QUEUED -> "◷" to false
            m.kind == Envelope.DM && m.status == Message.DELIVERED -> "✓✓" to true
            m.kind == Envelope.CHAT && everyone -> "✓✓" to true
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
        val mine = m.from == router.me.id
        val system = m.kind == Message.SYSTEM

        b.text.text = m.text
        b.time.text = timeFmt.format(Date(m.ts))
        b.ticks.text = row.ticks
        b.ticks.isVisible = row.ticks.isNotEmpty()
        b.ticks.setTextColor(b.root.context.getColor(if (row.blue) R.color.tick_delivered else R.color.tick))

        val lp = b.bubble.layoutParams as LinearLayout.LayoutParams
        when {
            system -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_system); lp.gravity = Gravity.CENTER_HORIZONTAL
                b.name.isVisible = true; b.name.text = "🌐 From the internet — via ${m.fromName}'s phone"
                b.name.setTextColor(b.root.context.getColor(R.color.chip_text))
            }
            mine -> {
                b.bubble.setBackgroundResource(if (row.grouped) R.drawable.bg_bubble_out_grouped else R.drawable.bg_bubble_out)
                lp.gravity = Gravity.END; b.name.isVisible = false
            }
            else -> {
                b.bubble.setBackgroundResource(if (row.grouped) R.drawable.bg_bubble_in_grouped else R.drawable.bg_bubble_in)
                lp.gravity = Gravity.START
                b.name.isVisible = row.showName
                if (row.showName) { b.name.text = m.fromName.ifEmpty { "Someone" }; b.name.setTextColor(nameColor(m.from)) }
            }
        }
        b.bubble.layoutParams = lp
        b.row.setPadding(b.row.paddingLeft, if (row.grouped) 2 else 8, b.row.paddingRight, 2)
        b.bubble.setOnClickListener { onMessageTap(m) }
    }

    companion object {
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val NAME_COLORS = intArrayOf(
            0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(), 0xFF3949AB.toInt(),
            0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
        )

        fun nameColor(id: String): Int = NAME_COLORS[(id.hashCode() and 0x7FFFFFFF) % NAME_COLORS.size]

        fun avatarColor(id: String): Int {
            val c = nameColor(id)
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
