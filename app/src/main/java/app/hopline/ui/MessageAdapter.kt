package app.hopline.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ItemMessageBinding
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.Router

class MessageAdapter(private val router: Router, private val showNames: Boolean) :
    ListAdapter<MessageAdapter.Row, MessageAdapter.VH>(DIFF) {

    /** Snapshot of what's drawn, so DiffUtil can tell when a status line changed. */
    data class Row(val m: Message, val meta: String)

    fun submit(messages: List<Message>) = submitList(messages.map { Row(it, metaFor(it)) })

    private fun metaFor(m: Message): String = when {
        m.from == router.me.id && m.kind != Message.SYSTEM -> Ui.time(m.ts) + " · " + Ui.myStatus(router, m)
        m.kind == Message.SYSTEM -> Ui.time(m.ts) + " · via ${m.fromName}'s phone"
        else -> Ui.time(m.ts)
    }

    class VH(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = getItem(position); val m = row.m
        val mine = m.from == router.me.id
        val b = h.b
        b.text.text = m.text
        b.meta.text = row.meta
        val lp = b.bubble.layoutParams as LinearLayout.LayoutParams
        when {
            m.kind == Envelope.SOS -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_sos); lp.gravity = Gravity.CENTER_HORIZONTAL
                b.name.visibility = View.VISIBLE; b.name.text = "🆘 HELP"
            }
            m.kind == Message.SYSTEM -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_system); lp.gravity = Gravity.CENTER_HORIZONTAL
                b.name.visibility = View.VISIBLE; b.name.text = "🌐 From the internet"
            }
            mine -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_mine); lp.gravity = Gravity.END
                b.name.visibility = View.GONE
            }
            else -> {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_theirs); lp.gravity = Gravity.START
                b.name.visibility = if (showNames) View.VISIBLE else View.GONE; b.name.text = m.fromName
            }
        }
        b.bubble.layoutParams = lp
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.m.id == b.m.id
            override fun areContentsTheSame(a: Row, b: Row) = a.meta == b.meta && a.m.text == b.m.text
        }
    }
}
