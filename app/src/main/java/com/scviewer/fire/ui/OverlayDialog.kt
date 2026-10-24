package com.scviewer.fire.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.scviewer.fire.R

/**
 * Reusable overlay dialog for the favorites / history / resume panels.
 *
 * Uses a Dialog (not a fragment) so the WebView stays mounted underneath —
 * cheaper than fragment transactions on Fire TV and avoids focus drift.
 *
 * [onActiveChange] tells the host activity when this dialog is on screen, so
 * D-pad keys are handed to the dialog's native focus and the virtual cursor
 * can dispatch its synthetic taps here instead of into the WebView below.
 */
class OverlayDialog(
    context: Context,
    private val title: String,
    private val items: List<OverlayItem>,
    private val emptyMessage: String,
    private val onItemClick: (OverlayItem) -> Unit,
    private val onAddCurrent: (() -> Unit)? = null,
    private val onActiveChange: ((Dialog?) -> Unit)? = null
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

        // TV navigation: the ListView itself holds focus and highlights the
        // selected position; rows must not steal/consume DPAD_CENTER.
        listView.itemsCanFocus = false
        listView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

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
        onActiveChange?.invoke(this)
        setOnDismissListener { onActiveChange?.invoke(null) }
        // Focus the first list item so the Fire TV remote lands on something.
        if (items.isNotEmpty()) {
            listView.post { listView.setSelection(0) }
            listView.requestFocus()
        } else {
            findViewById<Button>(R.id.overlayClose).requestFocus()
        }
    }
}
