package com.scviewer.fire.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.scviewer.fire.R

/**
 * Reusable overlay dialog for the favorites / history panels.
 *
 * Uses a Dialog (not a fragment) so the WebView stays mounted underneath —
 * cheaper than fragment transactions on Fire TV and avoids focus drift.
 */
class OverlayDialog(
    context: Context,
    private val title: String,
    private val items: List<OverlayItem>,
    private val emptyMessage: String,
    private val onItemClick: (OverlayItem) -> Unit,
    private val onAddCurrent: (() -> Unit)? = null
) : Dialog(context, R.style.SCOverlay) {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_list)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0.6f)
        }

        findViewById<TextView>(R.id.overlayTitle).text = title
        listView = findViewById(R.id.overlayList)
        emptyView = findViewById(R.id.overlayEmpty)

        val adapter = OverlayListAdapter(context, items.toMutableList())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            onItemClick(items[position])
            dismiss()
        }

        if (items.isEmpty()) {
            listView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            emptyView.text = emptyMessage
        } else {
            listView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }

        findViewById<Button>(R.id.overlayClose).setOnClickListener { dismiss() }

        val addBtn = findViewById<Button>(R.id.overlayAdd)
        if (onAddCurrent != null) {
            addBtn.visibility = View.VISIBLE
            addBtn.setOnClickListener {
                onAddCurrent.invoke()
                dismiss()
            }
        }
    }

    override fun show() {
        super.show()
        // Focus the first list item so the Fire TV remote lands on something.
        if (items.isNotEmpty()) {
            listView.post { listView.setSelection(0) }
            listView.requestFocus()
        }
    }
}
