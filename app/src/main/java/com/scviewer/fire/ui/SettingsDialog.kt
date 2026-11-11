package com.scviewer.fire.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.scviewer.fire.R
import com.scviewer.fire.data.SettingsStore

/**
 * "Impostazioni" dialog — hosts the cursor-speed control.
 *
 * Built with native views (SeekBar + buttons) so the Fire TV remote
 * navigates it out of the box: the bar gets focus automatically and
 * left/right on the D-pad changes the speed. The value is applied
 * immediately to any running [CursorController] (the speed is re-read on
 * every movement tick) and persisted via [SettingsStore].
 */
class SettingsDialog(
    context: Context,
    private val onSaved: (Int) -> Unit,
    private val onActiveChange: ((Dialog?) -> Unit)? = null
) : Dialog(context, R.style.SCOverlay) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_settings)
        window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0.6f)
        }

        val valueText = findViewById<TextView>(R.id.settingsSpeedValue)
        val seek = findViewById<SeekBar>(R.id.settingsSpeedSeek)

        val current = SettingsStore.getCursorSpeed(context)
        seek.max = SettingsStore.MAX_SPEED - SettingsStore.MIN_SPEED
        seek.progress = current - SettingsStore.MIN_SPEED
        valueText.text = context.getString(R.string.settings_speed_value, current)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = context.getString(
                    R.string.settings_speed_value,
                    progress + SettingsStore.MIN_SPEED
                )
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        // Qualità massima (3 stop: Auto/720p/480p), applicata subito.
        val qualityText = findViewById<TextView>(R.id.settingsQualityValue)
        val qualitySeek = findViewById<SeekBar>(R.id.settingsQualitySeek)
        val qualityLabels = listOf(
            context.getString(R.string.settings_quality_auto),
            context.getString(R.string.settings_quality_720),
            context.getString(R.string.settings_quality_480)
        )
        qualitySeek.progress = SettingsStore.getQualityStop(context)
        qualityText.text = qualityLabels[qualitySeek.progress.coerceIn(0, 2)]
        qualitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityText.text = qualityLabels[progress.coerceIn(0, 2)]
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        // UA alternativo (anti-blocco): toggle ON/OFF, salvato con Salva.
        val uaToggle = findViewById<Button>(R.id.settingsUaToggle)
        var uaValue = SettingsStore.getAlternateUa(context)
        uaToggle.text = context.getString(
            if (uaValue) R.string.settings_ua_on else R.string.settings_ua_off
        )
        uaToggle.setOnClickListener {
            uaValue = !uaValue
            uaToggle.text = context.getString(
                if (uaValue) R.string.settings_ua_on else R.string.settings_ua_off
            )
        }

        findViewById<Button>(R.id.settingsCancel).setOnClickListener { dismiss() }
        findViewById<Button>(R.id.settingsSave).setOnClickListener {
            val v = seek.progress + SettingsStore.MIN_SPEED
            SettingsStore.setCursorSpeed(context, v)
            SettingsStore.setQualityStop(context, qualitySeek.progress)
            SettingsStore.setAlternateUa(context, uaValue)
            onSaved.invoke(v)
            Toast.makeText(
                context,
                context.getString(R.string.settings_saved_toast, v),
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
        }
    }

    override fun show() {
        super.show()
        onActiveChange?.invoke(this)
        setOnDismissListener { onActiveChange?.invoke(null) }
        // Land the remote focus on the seek bar so left/right adjusts at once.
        findViewById<SeekBar>(R.id.settingsSpeedSeek).requestFocus()
    }
}
