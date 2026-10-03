package app.hopline.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ItemReactorBinding
import app.hopline.databinding.SheetEmojiBinding
import app.hopline.databinding.SheetReactionsBinding
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Core
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout

/** The two sheets around reactions: "who reacted" (tap the pill) and the full emoji set (tap +). */
object ReactionSheets {

    /**
     * WhatsApp's reactions sheet: an "All" tab plus one per emoji, everyone by name, and my own
     * row says "Tap to remove". It follows the mesh while open — a reaction hopping in shows up,
     * and the sheet closes itself if the message (or the group) goes away under it.
     * Returns the sheet so the screen can close it when it goes away; null if there was nothing to show.
     *
     * [stillShown] says whether the screen still shows [m] from [r] — the screen knows which chat
     * it reads from (the one on the radio, or one that was left) and which messages it has on it.
     * With no [onRemoveMine] the sheet is for looking only: a reaction can't be taken back in the
     * kept chat of a group that was left, nor on a message that has moved into the history.
     */
    fun showReactors(activity: AppCompatActivity, r: Router, m: Message, stillShown: () -> Boolean = { Core.router === r && r.message(m.id) != null },
                     onRemoveMine: (() -> Unit)?): BottomSheetDialog? {
        if (m.reactions.isEmpty() || activity.isFinishing || activity.isDestroyed) return null
        val sheet = BottomSheetDialog(activity)
        val sb = SheetReactionsBinding.inflate(activity.layoutInflater)
        sheet.setContentView(sb.root)
        val adapter = ReactorAdapter(r, canRemove = onRemoveMine != null) { who ->
            if (who == r.me.id) { sheet.dismiss(); onRemoveMine?.invoke() }
        }
        sb.list.layoutManager = LinearLayoutManager(activity)
        sb.list.adapter = adapter
        var filter: String? = null   // null = All

        fun label(key: String?, counts: List<Pair<String, Int>>): String =
            if (key == null) activity.getString(R.string.reactions_all, m.reactions.size)
            else "$key ${counts.firstOrNull { it.first == key }?.second ?: 0}"

        fun render() {
            if (!stillShown() || m.reactions.isEmpty()) { sheet.dismiss(); return }
            val counts = m.reactionCounts()
            // Rebuild tabs only when the set of emoji changed, keeping the selected one selected.
            val wanted = listOf<String?>(null) + counts.map { it.first }
            val have = (0 until sb.tabs.tabCount).map { sb.tabs.getTabAt(it)?.tag as String? }
            if (wanted != have) {
                if (filter != null && filter !in wanted) filter = null
                sb.tabs.clearOnTabSelectedListeners()
                sb.tabs.removeAllTabs()
                for (key in wanted) {
                    val label = label(key, counts)
                    val n = counts.firstOrNull { it.first == key }?.second ?: 0
                    sb.tabs.addTab(sb.tabs.newTab().setText(label).setTag(key).setContentDescription(
                        if (key == null) label else activity.resources.getQuantityString(R.plurals.chat_reactors_tab_desc, n, key, n)), key == filter)
                }
                sb.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) { filter = tab.tag as String?; render() }
                    override fun onTabUnselected(tab: TabLayout.Tab) {}
                    override fun onTabReselected(tab: TabLayout.Tab) {}
                })
            } else {
                for (i in 0 until sb.tabs.tabCount) {
                    val tab = sb.tabs.getTabAt(i) ?: continue
                    tab.text = label(tab.tag as String?, counts)
                }
            }
            // Me first (that's the row with the action), then everyone else in the order they reacted.
            val rows = m.reactions.entries
                .filter { filter == null || it.value == filter }
                .sortedBy { if (it.key == r.me.id) 0 else 1 }
                .map { it.key to it.value }
            adapter.submit(rows)
        }

        val obs = Observer<Int> { if (sheet.isShowing) render() }
        Core.version.observe(activity, obs)
        sheet.setOnDismissListener { Core.version.removeObserver(obs) }
        render()
        sheet.show()
        return sheet
    }

    private class ReactorAdapter(private val r: Router, private val canRemove: Boolean, private val onTap: (String) -> Unit) : RecyclerView.Adapter<ReactorAdapter.VH>() {
        class VH(val b: ItemReactorBinding) : RecyclerView.ViewHolder(b.root)
        private var rows: List<Pair<String, String>> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")   // a handful of rows, rebuilt wholesale
        fun submit(list: List<Pair<String, String>>) { rows = list; notifyDataSetChanged() }

        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemReactorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            val (who, emoji) = rows[i]
            val ctx = h.b.root.context
            val me = who == r.me.id
            // Namesakes get told apart here too — "who reacted" is exactly where it matters.
            val name = if (me) ctx.getString(R.string.reply_you) else Ui.uniqueName(r, who)
            h.b.name.text = name
            h.b.avatar.text = Ui.initial(if (me) r.me.name else Ui.nameOf(r, who))
            h.b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(who))
            h.b.emoji.text = emoji
            // My own row is the one with an action — where there is one to take.
            val mine = me && canRemove
            h.b.sub.visibility = if (mine) View.VISIBLE else View.GONE
            h.b.root.isClickable = mine
            h.b.root.isFocusable = mine
            if (mine) h.b.root.setOnClickListener { onTap(who) } else { h.b.root.setOnClickListener(null); h.b.root.isClickable = false }
            h.b.root.contentDescription = if (mine) ctx.getString(R.string.react_remove_desc, emoji) else "$name $emoji"
        }
    }

    /**
     * Every emoji, from the official Jetpack picker: categories, search-free browsing, skin tones
     * and a "recently used" row it remembers on its own. Only shows what this phone can draw.
     */
    fun showPicker(activity: AppCompatActivity, onPick: (String) -> Unit): BottomSheetDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val sheet = BottomSheetDialog(activity)
        val sb = SheetEmojiBinding.inflate(activity.layoutInflater)
        sheet.setContentView(sb.root)
        // A fixed, tall sheet: the picker scrolls inside it, so the sheet itself must not also
        // try to drag on every downward scroll.
        val h = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        sb.root.layoutParams = sb.root.layoutParams.apply { height = h }
        sheet.behavior.isDraggable = false
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sb.close.setOnClickListener { sheet.dismiss() }
        sb.picker.setOnEmojiPickedListener { item ->
            sheet.dismiss()
            onPick(item.emoji)
        }
        sheet.show()
        return sheet
    }
}
