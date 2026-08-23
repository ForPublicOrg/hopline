package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ActivityPeopleBinding
import app.hopline.databinding.ItemPersonBinding
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Core

/** Everyone the mesh has ever heard of in this group. Tap a person for a private chat. */
class PeopleActivity : AppCompatActivity() {
    private lateinit var b: ActivityPeopleBinding
    private val adapter = PeopleAdapter { p -> startActivity(Intent(this, ChatActivity::class.java).putExtra("peer", p.id)) }
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val r = Core.router ?: return
        b.toolbar.subtitle = getString(R.string.in_range_now, r.peopleInRange(), r.people.size)

        // A crowd needs a search box; a trekking group doesn't.
        b.search.visibility = if (r.people.size > 12) View.VISIBLE else View.GONE

        var people = r.people.values.toList()
        if (query.isNotEmpty()) people = people.filter { it.name.contains(query, ignoreCase = true) }
        val sorted = people.sortedWith(compareByDescending<Person> { r.isInRange(it) }.thenBy { it.hops }.thenBy { it.name.lowercase() })
        adapter.router = r
        adapter.submit(sorted)
        b.empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    class PeopleAdapter(private val onClick: (Person) -> Unit) : RecyclerView.Adapter<PeopleAdapter.VH>() {
        var router: Router? = null
        private var items: List<Person> = emptyList()
        fun submit(list: List<Person>) { items = list; notifyDataSetChanged() }

        class VH(val b: ItemPersonBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val p = items[i]; val r = router ?: return
            h.b.name.text = p.name.ifEmpty { "Someone" }
            h.b.avatar.text = p.name.take(1).uppercase().ifEmpty { "?" }
            h.b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(p.id))
            h.b.status.text = Ui.personStatus(r, p)
            val inRange = r.isInRange(p)
            h.b.dot.background.mutate().setTint(h.b.root.context.getColor(if (inRange) R.color.online else R.color.offline))
            h.b.badge.visibility = if (p.hasInternet && inRange) View.VISIBLE else View.GONE
            h.b.root.setOnClickListener { onClick(p) }
        }
    }
}
