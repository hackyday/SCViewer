package com.scviewer.fire.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.scviewer.fire.R

/**
 * Adapter for the favorites / history overlay list.
 * The items are simply (title, subtitle) pairs — caller handles the click.
 */
data class OverlayItem(val title: String, val subtitle: String, val tag: String)

class OverlayListAdapter(
    context: Context,
    private val items: MutableList<OverlayItem> = mutableListOf()
) : ArrayAdapter<OverlayItem>(context, R.layout.item_list, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_list, parent, false)
        val item = items[position]
        val tv = view as TextView
        tv.text = if (item.subtitle.isBlank()) item.title else "${item.title}\n${item.subtitle}"
        return view
    }

    fun replaceAll(newItems: List<OverlayItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
