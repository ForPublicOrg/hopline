package app.hopline.ui

import android.content.Intent
import android.os.Bundle
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

class PeopleActivity : AppCompatActivity() {
    private lateinit var b: ActivityPeopleBinding
    private val adapter = PeopleAdapter { p -> startActivity(Intent(this, ChatActivity::class.java).putExtra("peer", p.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.share.setOnCheckedChangeListener { _, on ->
            Core.router?.let { if (it.shareInternet != on) { it.shareInternet = on; it.sendPresence() } }
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val r = Core.router ?: return
        b.meName.text = "${r.me.name} (you)"
        b.meStatus.text = if (r.hasInternet) "Your phone has internet right now" else "No internet on your phone right now"
        b.share.isChecked = r.shareInternet
        val people = r.people.values.sortedWith(compareByDescending<Person> { r.isInRange(it) }.thenBy { it.hops }.thenBy { it.name.lowercase() })
        adapter.router = r
        adapter.submit(people)
        b.empty.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
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
            h.b.avatar.text = p.name.take(1).uppercase()
            h.b.status.text = Ui.personStatus(r, p)
            val inRange = r.isInRange(p)
            h.b.dot.background.setTint(h.b.root.context.getColor(if (inRange) R.color.online else R.color.offline))
            h.b.badge.visibility = if (p.hasInternet && inRange) View.VISIBLE else View.GONE
            h.b.root.setOnClickListener { onClick(p) }
        }
    }
}
