package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ActivityPeopleBinding
import app.hopline.databinding.ItemNoteBinding
import app.hopline.databinding.ItemPersonBinding
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Core
import java.util.Locale

/** Everyone the mesh has ever heard of in this group, me first. Tap a person for a private chat. */
class PeopleActivity : AppCompatActivity() {
    private lateinit var b: ActivityPeopleBinding
    private val adapter = PeopleAdapter(
        onMe = { Asks.myName(this) },
        onPerson = { p -> startActivity(Intent(this, ChatActivity::class.java).putExtra("peer", p.id)) },
    )
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        Asks.listen(this)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.search.doAfterTextChanged { s -> query = s?.toString()?.trim().orEmpty(); refresh() }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val r = Core.router
        val rows = ArrayList<PeopleAdapter.Row>()
        if (r == null) {
            b.toolbar.subtitle = null
            b.search.visibility = View.GONE
            rows += PeopleAdapter.Row.Me
            rows += PeopleAdapter.Row.Note(getString(R.string.people_starting))
            adapter.submit(rows, null)
            return
        }
        // "In range" counts against the group as it is now (heard from in 2 days), not everyone ever.
        val others = r.people.values.filter { it.id != r.me.id }
        b.toolbar.subtitle = getString(R.string.in_range_now, r.peopleInRange(), r.activePeople())

        // A crowd needs a search box; a trekking group doesn't. Keep it while someone is typing in it.
        b.search.visibility = if (others.size > 12 || query.isNotEmpty()) View.VISIBLE else View.GONE

        val q = query.lowercase(Locale.getDefault())
        val matches = if (q.isEmpty()) others else others.filter { Ui.uniqueName(r, it.id, it.name).lowercase(Locale.getDefault()).contains(q) }
        val sorted = matches.sortedWith(compareByDescending<Person> { r.isInRange(it) }.thenBy { it.hops }
            .thenBy { Ui.nameOf(r, it.id, it.name).lowercase(Locale.getDefault()) })
        if (q.isEmpty() || Core.store.name.lowercase(Locale.getDefault()).contains(q)) rows += PeopleAdapter.Row.Me
        sorted.forEach { rows += PeopleAdapter.Row.P(it) }
        when {
            others.isEmpty() -> rows += PeopleAdapter.Row.Note(getString(R.string.person_empty))
            sorted.isEmpty() -> rows += PeopleAdapter.Row.Note(getString(R.string.people_no_match, query))
        }
        adapter.submit(rows, r)
    }

    class PeopleAdapter(private val onMe: () -> Unit, private val onPerson: (Person) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        sealed class Row {
            object Me : Row()
            class P(val p: Person) : Row()
            class Note(val text: String) : Row()
        }

        private var router: Router? = null
        private var items: List<Row> = emptyList()
        fun submit(list: List<Row>, r: Router?) { items = list; router = r; notifyDataSetChanged() }

        class PersonVH(val b: ItemPersonBinding) : RecyclerView.ViewHolder(b.root)
        class NoteVH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = items.size
        override fun getItemViewType(i: Int) = if (items[i] is Row.Note) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 1) NoteVH(ItemNoteBinding.inflate(inf, parent, false)) else PersonVH(ItemPersonBinding.inflate(inf, parent, false))
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            when (val row = items[i]) {
                is Row.Me -> bindMe((h as PersonVH).b, onMe)
                is Row.P -> bindPerson((h as PersonVH).b, router ?: return, row.p) { onPerson(row.p) }
                is Row.Note -> (h as NoteVH).b.note.text = row.text
            }
        }
    }

    companion object {
        /** One person's row — shared with Group info so both lists look and read the same. */
        fun bindPerson(h: ItemPersonBinding, r: Router, p: Person, onClick: () -> Unit) {
            val ctx = h.root.context
            val name = Ui.nameOf(r, p.id, p.name)
            h.name.text = Ui.uniqueName(r, p.id, p.name)
            h.avatar.text = Ui.initial(name)
            h.avatar.background.mutate().setTint(MessageAdapter.avatarColor(p.id))
            h.status.text = Ui.personStatus(r, p)
            val inRange = r.isInRange(p)
            h.dot.visibility = View.VISIBLE
            h.dot.background.mutate().setTint(ctx.getColor(if (inRange) R.color.online else R.color.offline))
            h.badge.visibility = if (p.hasInternet && inRange) View.VISIBLE else View.GONE
            h.root.setOnClickListener { onClick() }
            ViewCompat.replaceAccessibilityAction(h.root, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                ctx.getString(R.string.action_private_chat), null)
        }

        /** My own row: "Vikas (you) · Tap to change your name". */
        fun bindMe(h: ItemPersonBinding, onClick: () -> Unit) {
            val ctx = h.root.context
            val name = Core.store.name
            h.name.text = ctx.getString(R.string.you_suffix, name)
            h.avatar.text = Ui.initial(name)
            h.avatar.background.mutate().setTint(MessageAdapter.avatarColor(Core.store.nodeId))
            h.status.text = ctx.getString(R.string.you_change_name)
            h.dot.visibility = View.GONE
            h.badge.visibility = View.GONE
            h.root.setOnClickListener { onClick() }
            ViewCompat.replaceAccessibilityAction(h.root, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                ctx.getString(R.string.edit_name), null)
        }
    }
}
