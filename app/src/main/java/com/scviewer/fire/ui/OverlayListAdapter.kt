package com.scviewer.fire.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.scviewer.fire.R

data class OverlayItem(
    val title: String,
    val subtitle: String,
    val tag: String,
    val imageUrl: String? = null,
    val progressPercent: Int = 0
)

class OverlayListAdapter(
    context: Context,
    private val items: MutableList<OverlayItem> = mutableListOf()
) : ArrayAdapter<OverlayItem>(context, R.layout.item_list, items) {

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_list, parent, false)
        val item = items[position]

        val titleView = view.findViewById<TextView>(R.id.itemText)
        val subtitleView = view.findViewById<TextView>(R.id.itemSubtitle)
        val progressBar = view.findViewById<ProgressBar>(R.id.itemProgressBar)
        val poster = view.findViewById<ImageView>(R.id.itemPoster)

        titleView.text = item.title

        if (item.subtitle.isBlank()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = item.subtitle
        }

        if (item.progressPercent in 1..99) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = item.progressPercent
        } else {
            progressBar.visibility = View.GONE
        }

        if (item.imageUrl.isNullOrBlank()) {
            poster.load(R.drawable.ic_sctv_logo) {
                transformations(RoundedCornersTransformation(10f))
            }
        } else {
            poster.load(item.imageUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(10f))
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
