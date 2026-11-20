package com.scviewer.fire.ui

import android.view.View
import android.widget.LinearLayout
import com.scviewer.fire.R

/**
 * Sidebar SCtv: un pannello che vive SOLO aperto, sopra il contenuto.
 * Non esiste lo stato collassato — su Fire TV il rendering del sito
 * copriva la rail a icone quando la pagina era caricata (compositing),
 * quindi la sidebar si mostra sempre espansa e copre il sito.
 *
 * Apertura: MENU o DPAD_LEFT al bordo sinistro del sito.
 * Chiusura: Destra, Indietro, OK su una voce, o pagine immersivE.
 */
class TvSidebarController(
    private val root: LinearLayout,
    private val onSearch: () -> Unit,
    private val onHome: () -> Unit,
    private val onResume: () -> Unit,
    private val onFavorites: () -> Unit,
    private val onHistory: () -> Unit,
    private val onReload: () -> Unit,
    private val onBack: () -> Unit,
    private val onSettings: () -> Unit,
    private val onOpenChanged: (Boolean) -> Unit
) {

    val isOpen: Boolean
        get() = root.visibility == View.VISIBLE

    private val navItems: List<LinearLayout> = listOf(
        root.findViewById(R.id.navItemSearch),
        root.findViewById(R.id.navItemHome),
        root.findViewById(R.id.navItemResume),
        root.findViewById(R.id.navItemFavorites),
        root.findViewById(R.id.navItemHistory),
        root.findViewById(R.id.navItemReload),
        root.findViewById(R.id.navItemBack),
        root.findViewById(R.id.navItemSettings)
    )

    init {
        bind(R.id.navItemSearch, onSearch)
        bind(R.id.navItemHome, onHome)
        bind(R.id.navItemResume, onResume)
        bind(R.id.navItemFavorites, onFavorites)
        bind(R.id.navItemHistory, onHistory)
        bind(R.id.navItemReload, onReload)
        bind(R.id.navItemBack, onBack)
        bind(R.id.navItemSettings, onSettings)
    }

    private fun bind(id: Int, action: () -> Unit) {
        root.findViewById<LinearLayout>(id).setOnClickListener {
            close()
            action()
        }
    }

    /** B18: prima voce VISIBILE della sidebar in ordine di LAYOUT — non un
     *  id hardcodato: l'ordine visivo del layout è la verità (oggi la prima
     *  voce è "Cerca", "Home" è la seconda). Scorre i figli diretti della
     *  root (l'ordine dei child corrisponde all'ordine verticale) e prende
     *  il primo focusable visibile. */
    private fun firstVisibleFocusableItem(): View? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.isFocusable) return c
        }
        return null
    }

    /** Apre la sidebar e mette il fuoco sulla prima voce visibile. */
    fun open() {
        root.visibility = View.VISIBLE
        onOpenChanged(true)
        (firstVisibleFocusableItem()
            ?: root.findViewById<LinearLayout>(R.id.navItemHome)).requestFocus()
    }

    /** Chiude la sidebar e restituisce il fuoco al contenuto. */
    fun close() {
        root.visibility = View.GONE
        onOpenChanged(false)
    }

    /** Le pagine titolo/watch non aprono la sidebar (immersivE). */
    fun setImmersive(immersive: Boolean) {
        if (immersive && isOpen) close()
    }
}
