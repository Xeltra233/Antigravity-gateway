package org.helix.gateway.ui

import android.view.LayoutInflater
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.helix.gateway.R
import org.helix.gateway.data.AntiRouteLevel
import org.helix.gateway.data.AntiRouteScrubScope
import org.helix.gateway.data.AntiRouteSeparator
import org.helix.gateway.data.GuardSettings
import org.helix.gateway.data.ModelReportMode
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings sheet for the outbound guard and model reporting.
 *
 * These used to be editable only through environment variables, which a phone
 * cannot set, so every option is exposed here with plain-language labels.
 */
class GuardSettingsSheet(
    private val activity: AppCompatActivity,
    private val onSave: (GuardSettings) -> Unit
) {

    private val levelLabels = listOf(
        R.string.settings_guard_level_off,
        R.string.settings_guard_level_wire,
        R.string.settings_guard_level_standard,
        R.string.settings_guard_level_deep
    )
    private val scopeLabels = listOf(R.string.settings_scope_leading, R.string.settings_scope_all)
    private val separatorLabels = listOf(R.string.settings_separator_hyphen, R.string.settings_separator_word_joiner)
    private val levelHints = listOf(
        R.string.hint_level_off,
        R.string.hint_level_wire,
        R.string.hint_level_standard,
        R.string.hint_level_deep
    )
    private val scopeHints = listOf(R.string.hint_scope_leading, R.string.hint_scope_all)
    private val separatorHints = listOf(R.string.hint_separator_hyphen, R.string.hint_separator_word_joiner)
    private val reportHints = listOf(
        R.string.hint_report_off,
        R.string.hint_report_headers,
        R.string.hint_report_body,
        R.string.hint_report_both
    )
    private val reportLabels = listOf(
        R.string.settings_model_report_off,
        R.string.settings_model_report_headers,
        R.string.settings_model_report_body,
        R.string.settings_model_report_both
    )

    fun show(current: GuardSettings) {
        val layout = LayoutInflater.from(activity).inflate(R.layout.dialog_guard_settings, null)

        val spLevel = layout.findViewById<Spinner>(R.id.spGuardLevel)
        val spScope = layout.findViewById<Spinner>(R.id.spScrubScope)
        val spSeparator = layout.findViewById<Spinner>(R.id.spSeparator)
        val spReport = layout.findViewById<Spinner>(R.id.spModelReport)
        val swWireEscape = layout.findViewById<SwitchMaterial>(R.id.swWireEscape)
        val swScrub = layout.findViewById<SwitchMaterial>(R.id.swScrub)
        val swSplit = layout.findViewById<SwitchMaterial>(R.id.swSplit)
        val swDemote = layout.findViewById<SwitchMaterial>(R.id.swDemotePrefill)
        val etReportKey = layout.findViewById<TextInputEditText>(R.id.etModelReportKey)
        val tvLevelHint = layout.findViewById<android.widget.TextView>(R.id.tvLevelHint)
        val tvScopeHint = layout.findViewById<android.widget.TextView>(R.id.tvScopeHint)
        val tvSeparatorHint = layout.findViewById<android.widget.TextView>(R.id.tvSeparatorHint)
        val tvReportHint = layout.findViewById<android.widget.TextView>(R.id.tvReportHint)

        bindSpinner(spLevel, levelLabels, AntiRouteLevel.entries.indexOf(current.antiRouteLevel))
        bindSpinner(spScope, scopeLabels, AntiRouteScrubScope.entries.indexOf(current.scrubScope))
        bindSpinner(spSeparator, separatorLabels, AntiRouteSeparator.entries.indexOf(current.separator))
        bindSpinner(spReport, reportLabels, ModelReportMode.entries.indexOf(current.modelReport))

        swWireEscape.isChecked = current.wireEscape
        swScrub.isChecked = current.scrub
        swSplit.isChecked = current.split
        swDemote.isChecked = current.demotePrefill
        etReportKey.setText(current.modelReportKey)

        // The hint under each spinner explains whatever option is currently selected.
        spLevel.onItemSelectedListener = HintListener(levelHints, tvLevelHint)
        spScope.onItemSelectedListener = HintListener(scopeHints, tvScopeHint)
        spSeparator.onItemSelectedListener = HintListener(separatorHints, tvSeparatorHint)
        spReport.onItemSelectedListener = HintListener(reportHints, tvReportHint)
        tvLevelHint.setText(levelHints[spLevel.selectedItemPosition])
        tvScopeHint.setText(scopeHints[spScope.selectedItemPosition])
        tvSeparatorHint.setText(separatorHints[spSeparator.selectedItemPosition])
        tvReportHint.setText(reportHints[spReport.selectedItemPosition])

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_title)
            .setView(layout)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.settings_reset, null)
            .create()

        dialog.setOnShowListener {
            // Resetting only fills the form; the user still confirms with 保存.
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val defaults = GuardSettings()
                bindSpinner(spLevel, levelLabels, AntiRouteLevel.entries.indexOf(defaults.antiRouteLevel))
                bindSpinner(spScope, scopeLabels, AntiRouteScrubScope.entries.indexOf(defaults.scrubScope))
                bindSpinner(spSeparator, separatorLabels, AntiRouteSeparator.entries.indexOf(defaults.separator))
                bindSpinner(spReport, reportLabels, ModelReportMode.entries.indexOf(defaults.modelReport))
                swWireEscape.isChecked = defaults.wireEscape
                swScrub.isChecked = defaults.scrub
                swSplit.isChecked = defaults.split
                swDemote.isChecked = defaults.demotePrefill
                etReportKey.setText(defaults.modelReportKey)
                Toast.makeText(activity, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reportKey = etReportKey.text?.toString()?.trim().orEmpty()
                if (reportKey.isEmpty()) {
                    etReportKey.error = activity.getString(R.string.settings_report_key_invalid)
                    return@setOnClickListener
                }
                onSave(
                    GuardSettings(
                        antiRouteLevel = AntiRouteLevel.entries[spLevel.selectedItemPosition],
                        wireEscape = swWireEscape.isChecked,
                        scrub = swScrub.isChecked,
                        split = swSplit.isChecked,
                        scrubScope = AntiRouteScrubScope.entries[spScope.selectedItemPosition],
                        demotePrefill = swDemote.isChecked,
                        separator = AntiRouteSeparator.entries[spSeparator.selectedItemPosition],
                        modelReport = ModelReportMode.entries[spReport.selectedItemPosition],
                        modelReportKey = reportKey
                    )
                )
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private inner class HintListener(
        private val hints: List<Int>,
        private val target: android.widget.TextView
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long
        ) {
            target.setText(hints[position.coerceIn(0, hints.lastIndex)])
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun bindSpinner(spinner: Spinner, labels: List<Int>, selected: Int) {
        val items = labels.map { activity.getString(it) }
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selected.coerceIn(0, items.lastIndex), false)
    }
}