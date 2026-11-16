package com.scviewer.fire.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.scviewer.fire.R

/**
 * Collapsible D-pad navigation rail for SCtv.
 *
 * - Collapsed (68dp): displays studio vector icons cleanly along the left edge.
 * - Expanded (228dp): smoothly widens whenever any sidebar item receives
 *   D-pad focus, revealing labels and active domain status.
 * - Collapses automatically when focus leaves the rail back into content.
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
    private val onExpandedChanged: (Boolean) -> Unit
) {
    private val collapsedPx = root.resources.getDimensionPixelSize(R.dimen.sctv_rail_collapsed_width)
    private val expandedPx = root.resources.getDimensionPixelSize(R.dimen.sctv_rail_expanded_width)

    private val labels: List<View> = listOf(
        root.findViewById(R.id.sidebarBrandTitle),
        root.findViewById(R.id.navLabelSearch),
        root.findViewById(R.id.navLabelHome),
        root.findViewById(R.id.navLabelResume),
        root.findViewById(R.id.navLabelFavorites),
        root.findViewById(R.id.navLabelHistory),
        root.findViewById(R.id.navLabelReload),
        root.findViewById(R.id.navLabelBack),
        root.findViewById(R.id.navLabelSettings),
        root.findViewById(R.id.sidebarDomainStatus)
    )

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

    private val domainStatus: TextView = root.findViewById(R.id.sidebarDomainStatus)

    var isExpanded: Boolean = false
        private set

    private var widthAnimator: ValueAnimator? = null

    init {
        bindClickAndFocus(R.id.navItemSearch, onSearch)
        bindClickAndFocus(R.id.navItemHome, onHome)
        bindClickAndFocus(R.id.navItemResume, onResume)
        bindClickAndFocus(R.id.navItemFavorites, onFavorites)
        bindClickAndFocus(R.id.navItemHistory, onHistory)
        bindClickAndFocus(R.id.navItemReload, onReload)
        bindClickAndFocus(R.id.navItemBack, onBack)
        bindClickAndFocus(R.id.navItemSettings, onSettings)
    }

    private fun bindClickAndFocus(id: Int, action: () -> Unit) {
        val item = root.findViewById<LinearLayout>(id)
        item.setOnClickListener {
            collapse()
            action()
        }
        item.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expand()
            } else {
                root.post {
                    if (navItems.none { it.hasFocus() }) {
                        collapse()
                    }
                }
            }
        }
    }

    fun focusSidebar() {
        root.visibility = View.VISIBLE
        expand()
        root.findViewById<LinearLayout>(R.id.navItemHome).requestFocus()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        labels.forEach { it.visibility = View.VISIBLE }
        animateWidth(root.layoutParams.width, expandedPx)
        onExpandedChanged(true)
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        labels.forEach { it.visibility = View.GONE }
        animateWidth(root.layoutParams.width, collapsedPx)
        onExpandedChanged(false)
    }

    fun setCinemaHidden(hidden: Boolean) {
        if (hidden) {
            collapse()
            root.visibility = View.GONE
        } else {
            root.visibility = View.VISIBLE
        }
    }

    fun setDomainText(host: String) {
        domainStatus.text = host
    }

    private fun animateWidth(from: Int, to: Int) {
        widthAnimator?.cancel()
        val start = if (from > 0) from else collapsedPx
        widthAnimator = ValueAnimator.ofInt(start, to).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = root.layoutParams
                lp.width = anim.animatedValue as Int
                root.layoutParams = lp
            }
            start()
        }
    }
}
