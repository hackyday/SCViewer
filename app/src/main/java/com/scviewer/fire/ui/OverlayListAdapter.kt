package com.scviewer.fire.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.scviewer.fire.R

/**
 * Adapter for the favorites / history / resume overlay list: riga con
 * copertina (dalla CDN del sito) + testo. Caller handles the click.
 */
data class OverlayItem(
    val title: String,
    val subtitle: String,
    val tag: String,
    val imageUrl: String? = null
)

class OverlayListAdapter(
    context: Context,
    private val items: MutableList<OverlayItem> = mutableListOf()
) : ArrayAdapter<OverlayItem>(context, R.layout.item_list, items) {

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_list, parent, false)
        val item = items[position]
        val tv = view.findViewById<TextView>(R.id.itemText)
        tv.text = if (item.subtitle.isBlank()) item.title else "${item.title}  ·  ${item.subtitle}"
        val poster = view.findViewById<ImageView>(R.id.itemPoster)
        if (item.imageUrl.isNullOrBlank()) {
            poster.load(R.drawable.ic_launcher_foreground) {
                transformations(RoundedCornersTransformation(8f))
            }
        } else {
            poster.load(item.imageUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(8f))
            }
        }
        return view
    }

    fun replaceAll(newItems: List<OverlayItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
