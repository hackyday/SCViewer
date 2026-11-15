package com.scviewer.fire

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.SiteCatalog
import com.scviewer.fire.data.TvRow
import com.scviewer.fire.data.TvTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un elemento verticale dell'interfaccia TV. */
sealed class RowUi {
    data class Hero(val title: TvTitle) : RowUi()
    data class List(val row: TvRow) : RowUi()
    object Search : RowUi()
}

/**
 * Prototipo interfaccia TV nativa (senza cursore): barra di tab, hero e
 * righe di poster tutti navigabili col D-pad. I dati arrivano dal JSON
 * Inertia delle pagine del sito (reverse engineering in [SiteCatalog]).
 * OK su un titolo apre il flusso esistente [MainActivity] sulla sua pagina
 * (con Riprendi, skip intro, ecc.). Non è la UI principale: si avvia
 * direttamente per i test.
 */
class TvBrowseActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var rowsView: RecyclerView
    private lateinit var tabsBar: LinearLayout
    private val rowsAdapter = RowsAdapter()

    /** Navigazione D-pad: riga corrente e card corrente tracciate a mano
     *  (-1 = focus sui tab). Il focus search nativo dei RecyclerView
     *  annidati è imprevedibile, quindi lo gestiamo noi in modo
     *  deterministico. */
    private var focusRow = -1
    private var focusCard = 0
    private var focusedTab = 0
    private val rowRecyclers = androidx.collection.SparseArrayCompat<RecyclerView>()

    private enum class Tab(val label: String, val path: String?) {
        HOME("HOME", null), FILM("FILM", "/it/movies"),
        SERIE("SERIE", "/it/tv-shows"), CERCA("CERCA", null)
    }
    private var currentTab = Tab.HOME
    private var searchInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_browse)
        rowsView = findViewById(R.id.tvRows)
        tabsBar = findViewById(R.id.tvTabs)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.adapter = rowsAdapter

        Tab.entries.forEachIndexed { tabIndex, tab ->
            val b = TextView(this).apply {
                text = tab.label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.sc_text_secondary, theme))
                setPadding(28, 10, 28, 10)
                background = resources.getDrawable(R.drawable.tv_tab_focus, theme)
                isFocusable = true
                setOnClickListener { selectTab(tab) }
                setOnFocusChangeListener { _, f -> if (f) focusedTab = tabIndex }
            }
            tabsBar.addView(b)
        }
        selectTab(Tab.HOME)
        tabsBar.getChildAt(0)?.requestFocus()
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        Tab.entries.forEachIndexed { i, t ->
            (tabsBar.getChildAt(i) as TextView).isSelected = (t == tab)
        }
        if (tab == Tab.CERCA) {
            rowsAdapter.submit(listOf<RowUi>(RowUi.Search))
            rowsView.post { findViewById<EditText>(R.id.tvSearchInput)?.requestFocus() }
        } else {
            load(tab)
        }
    }

    private fun load(tab: Tab) {
        val domain = DomainRepository.get(this).currentDomain()
        scope.launch {
            val rows: List<RowUi> = withContext(Dispatchers.IO) {
                runCatching {
                    when (tab) {
                        Tab.HOME -> buildList<RowUi> {
                            SiteCatalog.continueWatching(this@TvBrowseActivity, domain)
                                .takeIf { it.isNotEmpty() }
                                ?.let { add(RowUi.List(TvRow("Continua a guardare", it))) }
                            val site = SiteCatalog.slidersRows("/", domain)
                            // Hero: primo titolo della prima slider.
                            site.firstOrNull { it.items.isNotEmpty() }
                                ?.items?.firstOrNull()
                                ?.let { add(RowUi.Hero(it)) }
                            site.forEach { add(RowUi.List(it)) }
                        }
                        Tab.FILM, Tab.SERIE -> {
                            val site = SiteCatalog.slidersRows(tab.path!!, domain)
                            buildList<RowUi> {
                                site.firstOrNull { it.items.isNotEmpty() }
                                    ?.items?.firstOrNull()
                                    ?.let { add(RowUi.Hero(it)) }
                                site.forEach { add(RowUi.List(it)) }
                            }
                        }
                        Tab.CERCA -> listOf(RowUi.Search)
                    }
                }.getOrDefault(emptyList())
            }
            rowsAdapter.submit(rows)
            if (rows.isEmpty()) Toast.makeText(
                this@TvBrowseActivity,
                "Caricamento non riuscito — premi il tab per riprovare",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // -- Navigazione D-pad ----------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            searchInput?.hasFocus() != true
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (tryMove(-1, 0)) return true
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (tryMove(1, 0)) return true
                KeyEvent.KEYCODE_DPAD_UP -> if (tryMove(0, -1)) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (tryMove(0, 1)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** muove il fuoco; true se gestito (il default non deve intervenire). */
    private fun tryMove(dx: Int, dy: Int): Boolean {
        if (focusRow < 0) {
            // sui tab: sinistra/destra al sistema, giù entra nel contenuto
            if (dy > 0) {
                if (rowsAdapter.itemCount == 0) return false
                focusRow = 0; focusCard = 0
                applyFocus()
                return true
            }
            return false
        }
        val items = rowsAdapter.peek()
        if (focusRow >= items.size) return false
        when (items[focusRow]) {
            is RowUi.Search -> {
                // nel campo di ricerca: su esce, sinistra/destra sono del testo
                if (dy < 0) { focusRow = -1; refocusTab(); return true }
                if (dy > 0 && focusRow + 1 < items.size) {
                    focusRow += 1; focusCard = 0; applyFocus(); return true
                }
                return false
            }
            is RowUi.Hero -> {
                // hero a tutta larghezza: solo su/giù
                if (dx != 0) return false
                return moveRow(dy)
            }
            is RowUi.List -> {
                if (dy != 0) return moveRow(dy)
                val row = items[focusRow] as RowUi.List
                val n = focusCard + dx
                if (n < 0 || n >= row.row.items.size) return false
                focusCard = n
                applyFocus()
                return true
            }
        }
    }

    private fun moveRow(dy: Int): Boolean {
        val items = rowsAdapter.peek()
        val newRow = focusRow + dy
        if (newRow < 0) { focusRow = -1; refocusTab(); return true }
        if (newRow >= items.size) return false
        focusRow = newRow
        focusCard = focusCard.coerceIn(
            0, ((items[focusRow] as? RowUi.List)?.row?.items?.size ?: 1) - 1
        ).coerceAtLeast(0)
        applyFocus()
        return true
    }

    private fun refocusTab() {
        (tabsBar.getChildAt(focusedTab) as? TextView)?.requestFocus()
    }

    /** applica lo stato: porta la riga in vista e chiede il fuoco alla card. */
    private fun applyFocus() {
        if (focusRow < 0) return
        rowsView.scrollToPosition(focusRow)
        rowsView.post {
            val item = rowsAdapter.peek().getOrNull(focusRow) ?: return@post
            when (item) {
                is RowUi.Hero, is RowUi.Search -> {
                    rowsView.findViewHolderForAdapterPosition(focusRow)
                        ?.itemView?.requestFocus()
                }
                is RowUi.List -> {
                    val rv = rowRecyclers.get(focusRow) ?: return@post
                    val idx = focusCard.coerceIn(0, item.row.items.size - 1)
                    (rv.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(idx, 0)
                    rv.post {
                        rv.findViewHolderForAdapterPosition(idx)
                            ?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun openTitle(t: TvTitle) {
        // Il WebView deve ricevere un URL assoluto: un percorso relativo
        // verrebbe interpretato come file://.
        val domain = DomainRepository.get(this).currentDomain()
        val full = if (t.url.startsWith("http")) t.url
                   else domain.trimEnd('/') + t.url
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_url", full)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    // -- Adapter --------------------------------------------------------------

    inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<RowUi>()

        fun submit(list: List<RowUi>) {
            items.clear(); items.addAll(list)
            rowRecyclers.clear()
            notifyDataSetChanged()
        }

        fun peek(): List<RowUi> = items

        override fun getItemCount() = items.size
        override fun getItemViewType(pos: Int) = when (items[pos]) {
            is RowUi.Hero -> 0
            is RowUi.List -> 1
            RowUi.Search -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeroHolder(inf.inflate(R.layout.item_tv_hero, parent, false))
                2 -> SearchHolder(inf.inflate(R.layout.item_tv_search, parent, false))
                else -> RowHolder(inf.inflate(R.layout.item_tv_row, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val it = items[pos]) {
                is RowUi.Hero -> (h as HeroHolder).bind(it.title)
                is RowUi.List -> (h as RowHolder).bind(it.row, pos)
                RowUi.Search -> (h as SearchHolder).bind()
            }
        }
    }

    inner class HeroHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val bg = v.findViewById<ImageView>(R.id.tvHeroBackground)
        private val title = v.findViewById<TextView>(R.id.tvHeroTitle)
        private val meta = v.findViewById<TextView>(R.id.tvHeroMeta)
        private val plot = v.findViewById<TextView>(R.id.tvHeroPlot)
        fun bind(t: TvTitle) {
            title.text = t.name
            meta.text = listOfNotNull(
                t.type.takeIf { it.isNotEmpty() }?.let { if (it == "tv") "SERIE" else "FILM" },
                t.seasonsCount?.let { "$it stagioni" },
                t.score?.let { "★ $it" }
            ).joinToString("   ·   ")
            plot.text = t.plot ?: ""
            bg.load(t.backgroundUrl ?: t.posterUrl) { crossfade(true) }
            itemView.setOnClickListener { openTitle(t) }
        }
    }

    inner class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val label = v.findViewById<TextView>(R.id.tvRowLabel)
        private val list = v.findViewById<RecyclerView>(R.id.tvRowItems)
        fun bind(row: TvRow, position: Int) {
            label.text = row.label
            list.layoutManager =
                LinearLayoutManager(list.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = CardsAdapter(row.items)
            if (position >= 0) rowRecyclers.put(position, list)
        }
    }

    inner class SearchHolder(v: View) : RecyclerView.ViewHolder(v) {
        fun bind() {
            val input = itemView.findViewById<EditText>(R.id.tvSearchInput)
            searchInput = input
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    val q = input.text.toString().trim()
                    if (q.isNotEmpty()) {
                        val domain = DomainRepository.get(this@TvBrowseActivity).currentDomain()
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { SiteCatalog.search(q, domain) }
                            val rows = mutableListOf<RowUi>(RowUi.Search)
                            if (res.isNotEmpty()) rows.add(RowUi.List(TvRow("Risultati per \"$q\"", res)))
                            rowsAdapter.submit(rows)
                        }
                    }
                    true
                } else false
            }
        }
    }

    inner class CardsAdapter(private val items: List<TvTitle>) :
        RecyclerView.Adapter<CardsAdapter.CardHolder>() {

        inner class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
            val poster = v.findViewById<ImageView>(R.id.tvCardPoster)
            val name = v.findViewById<TextView>(R.id.tvCardName)
            val score = v.findViewById<TextView>(R.id.tvCardScore)
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tv_card, parent, false)
            return CardHolder(v)
        }

        override fun onBindViewHolder(h: CardHolder, pos: Int) {
            val t = items[pos]
            h.name.text = t.name
            h.score.text = t.score?.let { "★ $it" } ?: ""
            h.score.visibility = if (t.score.isNullOrBlank()) View.GONE else View.VISIBLE
            h.poster.load(t.posterUrl) { crossfade(true) }
            h.itemView.setOnClickListener { openTitle(t) }
            // Focus: ingrandisce la card e illumina il nome; il bordo rosso
            // è nel foreground del frame (tv_card_focus).
            h.itemView.setOnFocusChangeListener { v, f ->
                val s = if (f) 1.10f else 1.0f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                h.name.setTextColor(
                    resources.getColor(
                        if (f) R.color.sc_text_primary else R.color.sc_text_secondary, theme
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
