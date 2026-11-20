package com.scviewer.fire.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.scviewer.fire.R
import com.scviewer.fire.data.SettingsStore

/**
 * "Impostazioni" dialog — scelta interfaccia primaria (SCtv / sito) + controlli.
 *
 * Built with native views (SeekBar + buttons) so the Fire TV remote
 * navigates it out of the box. In cima c'è la riga segmented per la modalità
 * interfaccia: le sezioni legate al sito (puntatore, UA) sono visibili SOLO
 * in modalità "site", la qualità resta sempre accessibile. [onSaved] riceve
 * `modeChanged` così il chiamante (MainActivity o SctvActivity) può fare il
 * routing quando l'utente ha cambiato interfaccia.
 */
class SettingsDialog(
    context: Context,
    private val onSaved: (Boolean) -> Unit,
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

        // -- Interfaccia primaria (segmented in cima) -------------------------
        val initialMode = SettingsStore.getUiMode(context)
        var uiMode = initialMode
        val sctvBtn = findViewById<Button>(R.id.settingsModeSctv)
        val siteBtn = findViewById<Button>(R.id.settingsModeSite)
        val pointerSection = findViewById<View>(R.id.settingsPointerSection)
        val uaSection = findViewById<View>(R.id.settingsUaSection)

        fun renderModeRow() {
            val sctvSelected = uiMode == SettingsStore.UI_MODE_SCTV
            sctvBtn.setBackgroundResource(
                if (sctvSelected) R.drawable.bg_sctv_button_primary
                else R.drawable.bg_sctv_button_ghost
            )
            sctvBtn.setTextColor(
                context.getColor(
                    if (sctvSelected) R.color.sctv_text_primary
                    else R.color.sctv_text_secondary
                )
            )
            siteBtn.setBackgroundResource(
                if (sctvSelected) R.drawable.bg_sctv_button_ghost
                else R.drawable.bg_sctv_button_primary
            )
            siteBtn.setTextColor(
                context.getColor(
                    if (sctvSelected) R.color.sctv_text_secondary
                    else R.color.sctv_text_primary
                )
            )
            // Puntatore e UA esistono solo nel sito: fuori modalità site sono
            // GONE (non solo invisibili) così non restano nel ciclo di focus.
            val siteVisible = uiMode == SettingsStore.UI_MODE_SITE
            pointerSection.visibility = if (siteVisible) View.VISIBLE else View.GONE
            uaSection.visibility = if (siteVisible) View.VISIBLE else View.GONE
        }
        renderModeRow()

        sctvBtn.setOnClickListener {
            uiMode = SettingsStore.UI_MODE_SCTV
            renderModeRow()
        }
        siteBtn.setOnClickListener {
            uiMode = SettingsStore.UI_MODE_SITE
            renderModeRow()
        }

        // -- Velocità puntatore (solo modalità sito) --------------------------
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

        // Navigazione del sito: D-pad spaziale (default) o puntatore.
        val navToggle = findViewById<Button>(R.id.settingsNavToggle)
        var navPointer = SettingsStore.getSitePointerMode(context)
        navToggle.text = context.getString(
            if (navPointer) R.string.settings_nav_pointer else R.string.settings_nav_dpad
        )
        navToggle.setOnClickListener {
            navPointer = !navPointer
            navToggle.text = context.getString(
                if (navPointer) R.string.settings_nav_pointer else R.string.settings_nav_dpad
            )
        }

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
            val modeChanged = uiMode != initialMode
            val v = seek.progress + SettingsStore.MIN_SPEED
            SettingsStore.setUiMode(context, uiMode)
            SettingsStore.setCursorSpeed(context, v)
            SettingsStore.setQualityStop(context, qualitySeek.progress)
            SettingsStore.setAlternateUa(context, uaValue)
            SettingsStore.setSitePointerMode(context, navPointer)
            onSaved.invoke(modeChanged)
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
        // Land the remote focus on the first visible control: in modalità
        // SCtv la seek della velocità è GONE, quindi si parte dai bottoni
        // segmented; in modalità sito dalla seek velocità come prima.
        val first = findViewById<Button>(R.id.settingsModeSctv)
        val speedSeek = findViewById<SeekBar>(R.id.settingsSpeedSeek)
        if (SettingsStore.isSctvMode(context)) first.requestFocus()
        else speedSeek.requestFocus()
    }
}
