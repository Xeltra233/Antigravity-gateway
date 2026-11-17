package org.helix.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.helix.gateway.GatewayApplication
import org.helix.gateway.R
import org.helix.gateway.data.AntiRouteLevel
import org.helix.gateway.data.ModelReportMode
import org.helix.gateway.data.Provider
import org.helix.gateway.data.Skin
import org.helix.gateway.data.SkinPreferences
import org.helix.gateway.databinding.ActivityMainBinding
import org.helix.gateway.util.CrashReporter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        /** Debug-only intent extra used by the crash-recorder self test. */
        const val EXTRA_TEST_CRASH = "agw_test_crash"

        /** Heaviest weight the platform font picker will resolve; the sketch skin leans on it. */
        private const val HEAVY_WEIGHT = 900

        /** Holds the sketch stroke back until the window's enter animation has finished. */
        private const val REVEAL_HOLD_MS = 280L

        /** Page switch duration; matches the 300ms the HTML preview uses for its tab fades. */
        private const val PAGE_SWITCH_MS = 300L

        /** Ink bleed under glyphs. */
        private const val BLEED_RADIUS = 0.45f
        private const val BLEED_DX = 0.2f
        private const val BLEED_DY = 0.2f

        private const val SECONDARY_STROKE_DP = 1.0f
        private const val SECONDARY_CORNER_DP = 8f

        private const val GLASS_CARD_RADIUS_DP = 18f
        private const val GLASS_FIELD_RADIUS_DP = 14f
        private const val GLASS_FIELD_STROKE_DP = 1f
        private const val GLASS_FIELD_STROKE = 0x24FFFFFF
        private const val GLASS_RIPPLE = 0x33FFFFFF
        /** Provider pill over the ribbon: 11% silk blue idle, 20% while that provider is in use. */
        private const val SILK_PILL = 0x1C96AFE1
        private const val SILK_PILL_ON = 0x3396AFE1
        private const val SILK_HAIRLINE = 0x2EFFFFFF
        /** .cfg.on border in acr: rgba(122,162,255,.62). */
        private const val SILK_PILL_ON_STROKE = 0x9E7AA2FF.toInt()
        /** Sketch list row: #f4edda at rest, #f9f3e1 while active, #dff0cd for the chip. */
        private const val SKETCH_ROW_ON = 0xFFF9F3E1.toInt()
        private const val SKETCH_CHIP = 0xFFDFF0CD.toInt()

        /** Press feedback tinted with the sketch ink instead of the skin primary. */
        private const val SKETCH_RIPPLE = 0x333E4450
        /** Field rule weight, matching the card outline so frames and dividers read as one pen. */
        private const val SKETCH_FIELD_STROKE_DP = 1.0f
        private const val SKETCH_INK = SketchSpec.INK

        /** Page colour behind the cards in the sketch skin. */
        private const val SKETCH_PAGE = SketchSpec.PAPER
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var skinPreferences: SkinPreferences
    private var stylePopup: StyleMenuPopup? = null
    private val viewModel: MainViewModel by viewModels()
    private lateinit var providerAdapter: ProviderAdapter
    private var detailProviderId: String? = null
    /** The key field opens masked every time; the eye only reveals it for the current visit. */
    private var keyRevealed = false

    private var isInternalTextUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        skinPreferences = SkinPreferences(this)
        setTheme(skinPreferences.skin.themeRes)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindShell()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The provider page behaves like a page, so back closes it before leaving the app.
                if (binding.sheetProviderDetail.visibility == View.VISIBLE) {
                    closeProviderDetail()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        observeUiState()
        updateCrashLogEntry()
        handleIntent(intent)
        maybeTriggerTestCrash()
    }

    /**
     * Wires the freshly inflated shell. [rebuildShell] runs it again after a skin swap, so every
     * listener installed here has to be idempotent: it only touches the current [binding].
     */
    private fun bindShell() {
        setupProviderList()
        setupProviderDetail()
        binding.tvTestResult.movementMethod = ScrollingMovementMethod()
        binding.btnViewCrashLog.setOnClickListener { showCrashLogDialog() }
        setupListeners()
        applySketchSurface()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("set_upstream_url")
        val key = intent?.getStringExtra("set_upstream_key")
        val port = intent?.getIntExtra("set_port", -1) ?: -1
        if (url != null || key != null) {
            val current = viewModel.uiState.value.config.getCurrentProvider()
            val finalUrl = url ?: current?.upstreamUrl ?: ""
            val finalKey = key ?: current?.upstreamKey ?: ""
            viewModel.updateDraft(finalUrl, finalKey)
        }
        if (port in 1..65535) {
            viewModel.updatePort(port)
        }
    }

    /** Shows the crash entry point only when a report is actually stored. */
    private fun updateCrashLogEntry() {
        val count = CrashReporter.reports(this).size
        Log.i("CrashReporter", "stored crash reports: $count")
        binding.btnViewCrashLog.visibility = View.GONE
        if (count > 0) {
            binding.btnViewCrashLog.text = getString(R.string.crash_log_button, count)
        }
    }

    private fun showCrashLogDialog() {
        val report = CrashReporter.readLatest(this)
        if (report == null) {
            Toast.makeText(this, R.string.crash_log_empty, Toast.LENGTH_SHORT).show()
            updateCrashLogEntry()
            return
        }
        val content = layoutInflater.inflate(R.layout.dialog_crash_log, null)
        content.findViewById<TextView>(R.id.tvCrashReport).text = report
        content.findViewById<TextView>(R.id.tvCrashLocation).text =
            getString(R.string.crash_log_location, CrashReporter.describe(this))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_log_title)
            .setView(content)
            .setCancelable(true)
            .create()
        content.findViewById<View>(R.id.btnCrashClose).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btnCrashCopy).setOnClickListener {
            copyToClipboard(report)
            Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
        }
        content.findViewById<View>(R.id.btnCrashShare).setOnClickListener { shareCrashReport(report) }
        content.findViewById<View>(R.id.btnCrashClear).setOnClickListener {
            CrashReporter.clear(this)
            updateCrashLogEntry()
            Toast.makeText(this, R.string.crash_log_cleared, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("crash-log", text))
    }

    /** Without root or a PC the user can still hand the report to support through any app. */
    private fun shareCrashReport(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_log_title))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.crash_log_share))) }
    }

    /** Debug builds only: lets an adb launch crash on purpose to verify the recorder. */
    private fun maybeTriggerTestCrash() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_TEST_CRASH, false)) {
            throw RuntimeException("CrashReporter self-test (debug build)")
        }
    }

    override fun onResume() {
        super.onResume()
        // The dashboard is usable, so the startup crash counter can be reset. The in-memory counter
        // was read in Application.onCreate, so it has to be cleared too or the banner keeps showing
        // the pre-reset count for the rest of this launch.
        CrashReporter.markStartupHealthy(this)
        if (GatewayApplication.safeMode) {
            reportSafeMode()
            GatewayApplication.clearSafeMode()
            binding.tvSafeModeBanner.visibility = View.GONE
            binding.btnViewCrashLog.visibility = View.GONE
        }
        viewModel.loadConfigAndNetwork()
        updateCrashLogEntry()
        // The silk skin is the only one backed by a live GL surface; it starts and stops with the
        // activity so the backdrop never animates behind a locked screen.
        if (skinPreferences.skin == Skin.SILK) {
            binding.silkBackdrop.startBackdrop()
            applySilkSurface()
        } else {
            applyShellSurfaces()
        }
    }

    override fun onPause() {
        binding.silkBackdrop.stopBackdrop()
        super.onPause()
    }

    /** Explains why the app started without the optional initialisation after repeated crashes. */
    private fun reportSafeMode() {
        binding.tvSafeModeBanner.visibility = View.VISIBLE
        binding.tvSafeModeBanner.text = getString(
            R.string.crash_safe_mode_hint,
            GatewayApplication.startupAttempts
        )
        binding.btnViewCrashLog.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        // Skin toggle: the control rolls into a single bar and the picker hangs below it.
        binding.btnStyle.onToggle = { open ->
            if (open) showStyleMenu() else stylePopup?.dismiss()
        }

        // Guard and model-report settings (top-right, next to the skin toggle)
        binding.btnSettings.setOnClickListener {
            GuardSettingsSheet(this) { guard ->
                viewModel.updateGuardSettings(guard)
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }.show(viewModel.uiState.value.config.guard)
        }

        // Three-page shell. The bottom tabs are the only navigation, so each one also owns the
        // page it reveals; there is no back stack to keep in sync.
        setupTabs()
        binding.btnNewProvider.setOnClickListener { showCreateProviderDialog() }
        binding.btnOpenGuard.setOnClickListener {
            GuardSettingsSheet(this) { guard ->
                viewModel.updateGuardSettings(guard)
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }.show(viewModel.uiState.value.config.guard)
        }


        // Upstream URL & Key text watchers
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isInternalTextUpdating) return
                val url = binding.etUpstreamUrl.text?.toString() ?: ""
                val key = binding.etUpstreamKey.text?.toString() ?: ""
                viewModel.updateDraft(url, key)
            }
        }
        binding.etUpstreamUrl.addTextChangedListener(textWatcher)
        binding.etUpstreamKey.addTextChangedListener(textWatcher)

        // Gateway Port text watcher
        binding.etGatewayPort.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isInternalTextUpdating) return
                val portStr = binding.etGatewayPort.text?.toString()?.trim() ?: ""
                val port = portStr.toIntOrNull()
                if (port != null && port in 1..65535) {
                    viewModel.updatePort(port)
                }
            }
        })

        // Downstream Key Generate & Copy
        binding.btnGenerateKey.setOnClickListener {
            viewModel.regenerateDownstreamKey()
        }
        binding.btnCopyKey.setOnClickListener {
            val key = viewModel.uiState.value.config.downstreamKey
            if (key.isNotEmpty()) {
                copyToClipboard("Downstream Key", key)
                Toast.makeText(this, "已复制下游 Key", Toast.LENGTH_SHORT).show()
            }
        }

        // App Link Copy
        binding.btnCopyLink.setOnClickListener {
            val link = viewModel.uiState.value.appLink
            copyToClipboard("App Link", link)
            Toast.makeText(this, "已复制应用链接", Toast.LENGTH_SHORT).show()
        }

        // Start / Stop Gateway
        binding.btnStartStop.setOnClickListener {
            val currentState = viewModel.uiState.value.gatewayState
            if (currentState == GatewayState.RUNNING) {
                viewModel.stopGateway(this)
            } else {
                viewModel.startGateway(this)
            }
        }

        // Fetch Models
        // Fetch Models
        binding.btnFetchModels.setOnClickListener {
            if (viewModel.uiState.value.gatewayState != GatewayState.RUNNING) {
                Toast.makeText(this, R.string.gateway_not_running_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.fetchModels()
        }

        // Test Message
        binding.btnTestMessage.setOnClickListener {
            if (viewModel.uiState.value.gatewayState != GatewayState.RUNNING) {
                Toast.makeText(this, R.string.gateway_not_running_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val customModel = binding.etTestModelId.text?.toString() ?: ""
            viewModel.testMessage(customModel)
        }

        setupRows()
    }

    /**
     * The visible rows stand in for the outlined inputs the layout used to show. Every editor is
     * still in the tree, so a row only writes into its editor and the watcher above persists it;
     * no data path changes.
     */
    private fun setupRows() {
        binding.btnBack.setOnClickListener { finish() }
        binding.viewStatusSwitch.setOnClickListener { binding.btnStartStop.performClick() }

        binding.rowAddr.setOnClickListener {
            Toast.makeText(this, R.string.row_addr_fixed, Toast.LENGTH_SHORT).show()
        }
        binding.rowPort.setOnClickListener {
            editRowValue(
                title = getString(R.string.row_port_title),
                current = binding.etGatewayPort.text?.toString().orEmpty(),
                numeric = true,
                validate = { value ->
                    val port = value.toIntOrNull()
                    if (port == null || port !in 1..65535) getString(R.string.row_port_invalid) else null
                }
            ) { value ->
                binding.etGatewayPort.setText(value)
            }
        }
        binding.rowKey.setOnClickListener {
            val key = viewModel.uiState.value.config.downstreamKey
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.row_key_missing, Toast.LENGTH_SHORT).show()
            } else {
                GlassDialogs.message(
                    activity = this,
                    title = getString(R.string.row_key_title),
                    message = key,
                    confirmLabel = getString(R.string.copy),
                    cancelLabel = getString(android.R.string.cancel),
                    onConfirm = {
                        copyToClipboard("Downstream Key", key)
                        Toast.makeText(this, R.string.row_key_copied, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        binding.rowTestModel.setOnClickListener {
            editRowValue(getString(R.string.row_model_id_title), binding.etTestModelId.text?.toString().orEmpty()) { value ->
                binding.etTestModelId.setText(value)
            }
        }
        binding.rowGuardLevel.setOnClickListener { binding.btnOpenGuard.performClick() }
        binding.rowReport.setOnClickListener { binding.btnOpenGuard.performClick() }
        binding.rowRunLog.setOnClickListener {
            val message = viewModel.uiState.value.statusMessage.ifBlank { getString(R.string.row_run_log_empty) }
            GlassDialogs.message(
                activity = this,
                title = getString(R.string.row_run_log_title),
                message = message,
                confirmLabel = getString(android.R.string.ok)
            )
        }
        binding.rowCrashLog.setOnClickListener { showCrashLogDialog() }
    }

    /** One dialog for every editable row: a single field on the skin's own panel. */
    private fun editRowValue(
        title: String,
        current: String,
        numeric: Boolean = false,
        password: Boolean = false,
        hint: String? = null,
        validate: ((String) -> String?)? = null,
        onCommit: (String) -> Unit
    ) {
        val inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        GlassDialogs.input(
            activity = this,
            title = title,
            value = current,
            hint = hint,
            inputType = inputType,
            validate = validate,
            onCommit = onCommit
        )
    }

    /** Material wraps its own inset background, so the glass panel is set on the window. */
    private fun applyDialogSurface(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.pv_dialog_bg))
        dialog.window?.setLayout(
            (dialog.window?.attributes?.width ?: 0),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Pushes the current state into the row values, the status header and the status switch. */
    private fun syncRowValues(state: UiState) {
        val config = state.config
        binding.tvRowAddr.text = "127.0.0.1"
        binding.tvRowPort.text = config.port.toString()
        binding.tvRowKey.text = if (config.downstreamKey.isEmpty()) "—" else "••••••••"
        binding.tvRowTestModel.text = binding.etTestModelId.text?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.row_model_id_default)
        binding.tvProviderEmpty.visibility = if (config.providers.isEmpty()) View.VISIBLE else View.GONE
        binding.layoutProviderList.visibility =
            if (config.providers.isEmpty()) View.GONE else View.VISIBLE
        binding.tvGatewayTitle.text = when (state.gatewayState) {
            GatewayState.RUNNING -> getString(R.string.row_status_running)
            GatewayState.STARTING -> getString(R.string.row_status_starting)
            GatewayState.STOPPING -> getString(R.string.row_status_stopping)
            GatewayState.ERROR -> getString(R.string.row_status_error)
            else -> getString(R.string.status_stopped)
        }
        binding.tvGatewaySub.text = state.statusMessage.ifBlank {
            getString(R.string.row_status_listen, config.port)
        }
        binding.viewStatusSwitch.background =
            statusSwitchDrawable(state.gatewayState == GatewayState.RUNNING)
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUi(state)
                }
            }
        }
    }

    private fun renderUi(state: UiState) {
        val currentProvider = state.config.getCurrentProvider()

        // The provider page and the routing summary are driven by the same state object, so they
        // stay correct after create / rename / delete without extra callbacks.
        renderProviderList(state)
        updateGuardSummary(state)
        syncRowValues(state)
        // 1. Provider header

        // 2. Input fields (update only if changed to avoid cursor jumping)
        isInternalTextUpdating = true
        val targetUrl = currentProvider?.upstreamUrl ?: ""
        if (binding.etUpstreamUrl.text?.toString() != targetUrl) {
            binding.etUpstreamUrl.setText(targetUrl)
        }
        val targetKey = currentProvider?.upstreamKey ?: ""
        if (binding.etUpstreamKey.text?.toString() != targetKey) {
            binding.etUpstreamKey.setText(targetKey)
        }
        val targetDownstream = state.config.downstreamKey
        if (binding.etDownstreamKey.text?.toString() != targetDownstream) {
            binding.etDownstreamKey.setText(targetDownstream)
        }
        val targetPort = state.config.port.toString()
        if (binding.etGatewayPort.text?.toString() != targetPort && !binding.etGatewayPort.hasFocus()) {
            binding.etGatewayPort.setText(targetPort)
        }
        val canEditPort = (state.gatewayState == GatewayState.IDLE || state.gatewayState == GatewayState.ERROR)
        binding.etGatewayPort.isEnabled = canEditPort

        if (state.statusMessage.contains("端口重复") || state.testResult.contains("端口重复")) {
            binding.tilGatewayPort.error = getString(R.string.port_duplicate_hint)
            binding.etGatewayPort.requestFocus()
        } else {
            binding.tilGatewayPort.error = null
        }

        // Auto populate test model input if empty and models fetched
        if (binding.etTestModelId.text.isNullOrBlank() && state.lastFetchedModels.isNotEmpty()) {
            binding.etTestModelId.setText(state.lastFetchedModels.first())
        }
        isInternalTextUpdating = false

        // 3. Downstream Key Restart Hint
        binding.tvKeyRestartHint.visibility = if (state.keyNeedsRestart) View.VISIBLE else View.GONE
        binding.tvGuardRestartHint.visibility = if (state.guardNeedsRestart) View.VISIBLE else View.GONE

        // 4. App Link
        binding.tvAppLink.text = state.appLink

        // 5. Gateway Start / Stop Button & State
        when (state.gatewayState) {
            GatewayState.IDLE -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = getString(R.string.start_gateway)
                paintStartButton(com.google.android.material.R.attr.colorPrimary)
            }
            GatewayState.STARTING -> {
                binding.btnStartStop.isEnabled = false
                binding.btnStartStop.text = "正在启动..."
            }
            GatewayState.RUNNING -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = getString(R.string.stop_gateway)
                paintStartButton(com.google.android.material.R.attr.colorError)
            }
            GatewayState.STOPPING -> {
                binding.btnStartStop.isEnabled = false
                binding.btnStartStop.text = "正在停止..."
            }
            GatewayState.ERROR -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = "重试启动"
                paintStartButton(com.google.android.material.R.attr.colorPrimary)
            }
        }

        // Both test actions stay tappable before the gateway runs, so a tap can tell the user what
        // is missing instead of the button silently swallowing the press. Only an in-flight request
        // disables them.
        val gatewayRunning = state.gatewayState == GatewayState.RUNNING
        binding.btnFetchModels.isEnabled = !state.isTesting
        binding.btnTestMessage.isEnabled = !state.isTesting
        val testActionAlpha = if (gatewayRunning) 1f else 0.45f
        binding.btnFetchModels.alpha = testActionAlpha
        binding.btnTestMessage.alpha = testActionAlpha

        // 6. Test progress & result
        binding.pbTesting.visibility = if (state.isTesting) View.VISIBLE else View.GONE
        binding.tvTestResult.text = state.testResult

        // Status Toast if any
        if (state.statusMessage.isNotEmpty()) {
            Toast.makeText(this, state.statusMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }
    }

    /**
     * Replaces the flat card surfaces with the hand-drawn paper card, and thickens the headline
     * text so the sketch skin reads as ink on paper rather than a recoloured Material layout.
     * Only runs for the sketch skin; every other skin keeps the stock surfaces.
     */
    private var sketchRevealPlayed = false

    private fun applySketchSurface() {
        if (skinPreferences.skin != Skin.SKETCH) return
        val density = resources.displayMetrics.density
        val headlinePx = 16f * resources.displayMetrics.scaledDensity
        val bodyPx = 13.5f * resources.displayMetrics.scaledDensity
        // The page gets its own grain: cards are lighter than the sheet, and a flat backdrop makes
        // the paper read as a tinted rectangle.
        binding.root.background = PaperSurfaceDrawable(density, SKETCH_PAGE)
        decorate(binding.root, density, headlinePx, bodyPx)
        scheduleSketchReveal()
    }

    /**
     * Draws the outline on starting from the first frame that is actually on screen.
     *
     * A Drawable learns its bounds during layout, and the window gets focus before it is composited,
     * so both of those fire while the app is still a black rectangle: the stroke would be most of the
     * way round by the time anything was visible. The pre-draw hook is the last point before the first
     * frame is rasterised, so percent zero lands in that frame.
     */
    private fun scheduleSketchReveal() {
        if (sketchRevealPlayed) return
        // Registration has to wait for a message-loop turn: during onCreate the root is not attached
        // yet, and the observer handed out then is torn down and replaced before anything is drawn.
        binding.root.post {
            if (sketchRevealPlayed || isFinishing) return@post
            val observer = binding.root.viewTreeObserver
            if (!observer.isAlive) {
                startSketchReveal()
                return@post
            }
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val current = binding.root.viewTreeObserver
                    if (current.isAlive) current.removeOnPreDrawListener(this)
                    startSketchReveal()
                    return true
                }
            })
        }
    }

    private fun startSketchReveal() {
        if (sketchRevealPlayed) return
        if (skinPreferences.skin != Skin.SKETCH) return
        sketchRevealPlayed = true
        // The first frame is rasterised behind the window's enter animation, so the opening stretch
        // of the stroke would land while the app is still fading in. Holding it back past that fade
        // is what makes the pen stroke a thing the user sees rather than a thing that already
        // happened.
        binding.root.postDelayed({ revealSketchStrokes(binding.root) }, REVEAL_HOLD_MS)
    }

    private fun revealSketchStrokes(view: View) {
        sketchCardOf(view)?.restartReveal()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) revealSketchStrokes(view.getChildAt(index))
        }
    }

    /**
     * Finds a sketch card behind whatever wrapper the framework put on the view.
     *
     * The card is not always the background itself: a clickable view wraps it in a ripple, and a
     * ripple with no content layer reports zero layers, so indexing layer 0 is not safe.
     */
    private fun sketchCardOf(view: View): SketchCardDrawable? {
        val background = view.background ?: return null
        if (background is SketchCardDrawable) return background
        if (background is LayerDrawable) {
            for (index in 0 until background.numberOfLayers) {
                val layer = background.getDrawable(index)
                if (layer is SketchCardDrawable) return layer
            }
        }
        return null
    }

    /**
     * Replaces the flat translucent panels with the frosted ones. The web build gets this from
     * `backdrop-filter`; here the panel is rebuilt from the same gradient stack, so the two builds
     * have to be compared on the drawn result rather than on the CSS.
     */
    private fun applySilkSurface() {
        val density = resources.displayMetrics.density
        decorateSilk(binding.root, density)
        applyShellSurfaces()
    }

    /**
     * Repaints the shell for the three non-silk skins. The rebuilt layout carries the silk palette
     * in its drawables, so on white and on paper every surface would keep its frosted blue fill;
     * each tagged surface is rebuilt from the active theme instead.
     */
    private fun applyShellSurfaces() {
        val skin = skinPreferences.skin
        if (skin == Skin.SILK) return
        val density = resources.displayMetrics.density
        val surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        val cardFill = if (skin == Skin.MIN_DARK) resolveThemeColor(R.attr.agwLogBg) else surface
        val cardStroke = resolveThemeColor(R.attr.agwCardBorder)
        val fieldFill = resolveThemeColor(R.attr.agwLogBg)
        val fieldStroke = resolveThemeColor(R.attr.agwFieldStroke)
        binding.viewStatusDot.background = dotDrawable(resolveThemeColor(android.R.attr.colorAccent))
        eachView(binding.root) { view ->
            when (view.tag as? String) {
                "card" -> view.background =
                    if (skin == Skin.SKETCH) SketchCardDrawable(density)
                    else roundedFill(cardFill, cardStroke, 18f)
                "field" -> view.background =
                    if (skin == Skin.SKETCH) roundedFill(surface, fieldStroke, 8f, 1.6f)
                    else roundedFill(fieldFill, fieldStroke, 12f)
                "ghost" -> view.background = roundedFill(Color.TRANSPARENT, fieldStroke, 12f)
                "result" -> view.background = roundedFill(fieldFill, cardStroke, 12f)
                "tabs" -> view.background = roundedFill(surface, 0, 0f, 0f)
            }
        }
    }

    /** .dot::before: accent core inside a 16% halo, so the indicator follows the skin accent. */
    private fun dotDrawable(color: Int): Drawable {
        val density = resources.displayMetrics.density
        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(0x29, Color.red(color), Color.green(color), Color.blue(color)))
        }
        val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val layers = LayerDrawable(arrayOf(halo, core))
        val inset = (7 * density).toInt()
        layers.setLayerInset(1, inset, inset, inset, inset)
        return layers
    }

    private fun eachView(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) eachView(view.getChildAt(index), action)
        }
    }

    /**
     * The silk switch is a white-on-glass pill; on the light skins that reads as nothing at all,
     * so the same geometry is rebuilt from the skin's accent and rule colours.
     */
    private fun statusSwitchDrawable(on: Boolean): Drawable? {
        if (skinPreferences.skin == Skin.SILK) {
            // Fresh instance per call: the cached drawable is shared, and attaching it to a second
            // view raises "already belongs to another owner" for layer-lists without a constant state.
            val id = if (on) R.drawable.pv_switch else R.drawable.pv_switch_off
            return ContextCompat.getDrawable(this, id)?.constantState?.newDrawable(resources)?.mutate()
        }
        val density = resources.displayMetrics.density
        val track = if (on) resolveThemeColor(R.attr.agwSwitchOn) else resolveThemeColor(R.attr.agwSwitchOff)
        val trackShape = GradientDrawable().apply {
            cornerRadius = 13.5f * density
            setColor(track)
            // The sketch switch is an inked outline over a flat swatch, not a soft pill.
            if (skinPreferences.skin == Skin.SKETCH) {
                setStroke((1.6f * density).roundToInt(), resolveThemeColor(R.attr.agwFieldStroke))
            }
        }
        val thumbShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val layers = LayerDrawable(arrayOf(trackShape, thumbShape))
        val pad = (3 * density).toInt()
        val width = (52 * density).toInt()
        val thumb = (21 * density).toInt()
        layers.setLayerInset(
            1,
            if (on) width - thumb - pad else pad,
            pad,
            if (on) pad else width - thumb - pad,
            pad
        )
        return layers
    }

    private fun roundedFill(fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            if (strokeDp > 0f) {
                setStroke((strokeDp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1), stroke)
            }
        }

    private fun decorateSilk(view: View, density: Float) {
        // The top bar is bare in the HTML preview, so it lets the ribbon through untouched; the tab
        // bar keeps its 62% glass plate, which is the only chrome the reference draws down there.
        when (view.id) {
            R.id.layoutTopBar -> view.setBackgroundColor(Color.TRANSPARENT)
            R.id.layoutTabs -> view.background =
                GlassSurfaceDrawable(density, 0f, GlassSurfaceDrawable.Variant.TABS)
        }
        when (view) {
            is MaterialCardView -> {
                view.setCardBackgroundColor(Color.TRANSPARENT)
                view.cardElevation = 0f
                view.background = GlassSurfaceDrawable(density, GLASS_CARD_RADIUS_DP, GlassSurfaceDrawable.Variant.CARD)
            }
            is TextInputLayout -> {
                val radius = GLASS_FIELD_RADIUS_DP * density
                view.boxBackgroundColor = Color.TRANSPARENT
                view.setBoxCornerRadii(radius, radius, radius, radius)
                view.boxStrokeWidth = (GLASS_FIELD_STROKE_DP * density).roundToInt().coerceAtLeast(1)
                view.boxStrokeWidthFocused = ((GLASS_FIELD_STROKE_DP + 0.6f) * density).roundToInt().coerceAtLeast(1)
                view.boxStrokeColor = GLASS_FIELD_STROKE
                view.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
            }
            is MaterialButton -> {
                // The primary action and the text-only button keep their own treatment.
                if (view.id == R.id.btnStartStop || view.id == R.id.btnViewCrashLog) return
                view.backgroundTintList = null
                view.background = GlassSurfaceDrawable(density, GLASS_FIELD_RADIUS_DP, GlassSurfaceDrawable.Variant.FIELD)
                view.setTextColor(Color.WHITE)
                view.strokeWidth = 0
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) decorateSilk(view.getChildAt(index), density)
        }
    }

    private fun decorate(view: View, density: Float, headlinePx: Float, bodyPx: Float) {
        when (view) {
            is MaterialCardView -> {
                view.setCardBackgroundColor(Color.TRANSPARENT)
                view.cardElevation = 0f
                view.background = SketchCardDrawable(density)
            }
            is TextInputLayout -> {
                // Same pen as the card outline: the rounded width matters, because a 1px rule of ink
                // is smeared by anti-aliasing until it reads lighter than the card border it sits in.
                val stroke = (SKETCH_FIELD_STROKE_DP * density).roundToInt().coerceAtLeast(2)
                val corner = 8f * density
                view.setBoxCornerRadii(corner, corner, corner, corner)
                view.boxStrokeWidth = stroke
                view.boxStrokeWidthFocused = stroke
                view.boxStrokeColor = SketchSpec.INK_SUB
            }
            is TextView -> {
                if (view.textSize >= headlinePx) {
                    view.setTypeface(heavyTypeface(view.typeface))
                    view.setTextColor(SketchSpec.TEXT)
                } else if (view.textSize >= bodyPx) {
                    view.setTextColor(SketchSpec.TEXT)
                } else {
                    // Labels and captions sit back from the body; on paper they are lighter graphite,
                    // not a smaller font.
                    view.setTextColor(SketchSpec.TEXT_SUB)
                }
                // Ink spreads a hair on paper; a sub-pixel shadow is what stops the type from looking
                // like it was printed on top of the sheet.
                view.setShadowLayer(BLEED_RADIUS * density, BLEED_DX * density, BLEED_DY * density, SketchSpec.TEXT_BLEED)
            }
            is MaterialButton -> {
                // Secondary buttons keep a drawn edge instead of the stock Material stroke.
                view.strokeWidth = (SECONDARY_STROKE_DP * density).roundToInt()
                view.strokeColor = ColorStateList.valueOf(SketchSpec.INK_SUB)
                view.cornerRadius = (SECONDARY_CORNER_DP * density).roundToInt()
                view.setTextColor(SketchSpec.TEXT)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) decorate(view.getChildAt(index), density, headlinePx, bodyPx)
        }
    }

    /**
     * Colours the primary action button. The sketch skin replaces the flat fill with the crayon slab,
     * and every state change routes through here so the hand-drawn surface is not lost when the label
     * and colour flip between start and stop.
     */
    private fun paintStartButton(colorAttr: Int) {
        paintPrimaryButton(binding.btnStartStop, colorAttr)
    }

    /** Primary action slab for the current skin: sketch crayon, silk glass, or a flat tinted fill. */
    private fun paintPrimaryButton(button: TextView, colorAttr: Int) {
        if (skinPreferences.skin == Skin.SKETCH) {
            button.backgroundTintList = null
            val slab = SketchButtonDrawable(resources.displayMetrics.density)
            button.background = RippleDrawable(ColorStateList.valueOf(SKETCH_RIPPLE), slab, null)
        } else if (skinPreferences.skin == Skin.SILK) {
            button.backgroundTintList = null
            val glass = GlassSurfaceDrawable(resources.displayMetrics.density, GLASS_CARD_RADIUS_DP, GlassSurfaceDrawable.Variant.PRIMARY)
            button.background = RippleDrawable(ColorStateList.valueOf(GLASS_RIPPLE), glass, null)
            button.setTextColor(Color.WHITE)
        } else {
            button.backgroundTintList = null
            button.background = roundedFill(themeColor(colorAttr), 0, 16f, 0f)
            button.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary))
        }
    }

    /** Resolves a theme attribute so dynamic colors follow the active skin. */
    /**
     * Heaviest available face for headings.
     *
     * `Typeface.create(family, weight, italic)` is API 28 while the app still supports 26, so older
     * devices fall back to the bold face rather than skipping the weight change entirely.
     */
    private fun heavyTypeface(base: android.graphics.Typeface): android.graphics.Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.Typeface.create(base, HEAVY_WEIGHT, false)
        } else {
            android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD)
        }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return 0
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun showStyleMenu() {
        if (stylePopup?.isShowing == true) return
        val popup = StyleMenuPopup(binding.btnStyle, skinPreferences.skin) { skin ->
            switchSkin(skin)
        }
        popup.setOnDismissListener {
            stylePopup = null
            binding.btnStyle.setOpen(false)
        }
        stylePopup = popup
        popup.show()
    }

    /**
     * Swaps skins in place. The old path called [android.app.Activity.recreate], which destroyed
     * the window and only painted again once the replacement had been inflated — every switch
     * showed a blank frame in between. Restyling the live theme and rebuilding the hierarchy keeps
     * the old palette on screen until the new one is ready, so the switch lands on one frame.
     */
    private fun switchSkin(skin: Skin) {
        if (skin == skinPreferences.skin) return
        skinPreferences.skin = skin
        theme.applyStyle(skin.themeRes, true)
        rebuildShell()
    }

    /** Re-inflates the shell against the restyled theme and puts the user back where they were. */
    private fun rebuildShell() {
        val page = currentPage
        val openDetailId = if (binding.sheetProviderDetail.visibility == View.VISIBLE) {
            detailProviderId
        } else {
            null
        }
        val firstVisible = (binding.recyclerProviders.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: 0

        stylePopup?.dismiss()
        stylePopup = null
        binding.btnStyle.setOpen(false)
        binding.silkBackdrop.stopBackdrop()
        binding.sheetProviderDetail.animate().cancel()
        binding.layoutContentRoot.animate().cancel()

        // cloneInContext keeps AppCompat's view factory, so <TextView> and friends are still
        // promoted for the new skin instead of inflating as platform widgets.
        val themed = ContextThemeWrapper(this, skinPreferences.skin.themeRes)
        binding = ActivityMainBinding.inflate(layoutInflater.cloneInContext(themed))
        setContentView(binding.root)
        applyWindowChrome()

        bindShell()
        renderUi(viewModel.uiState.value)
        selectPage(page, animate = false)
        if (firstVisible > 0) binding.recyclerProviders.scrollToPosition(firstVisible)
        detailProviderId = null
        openDetailId?.let { id ->
            viewModel.uiState.value.config.providers.firstOrNull { it.id == id }
                ?.let { openProviderDetail(it) }
        }
        updateCrashLogEntry()

        // onResume owns the backdrop for a normal launch; an in-place rebuild never pauses, so the
        // silk surface and the flat shell fills have to be started by hand here.
        if (skinPreferences.skin == Skin.SILK) {
            binding.silkBackdrop.startBackdrop()
            applySilkSurface()
        } else {
            applyShellSurfaces()
        }
    }

    /** Repaints the window chrome so the frame behind the hierarchy follows the skin too. */
    private fun applyWindowChrome() {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
            window.setBackgroundDrawable(
                if (value.resourceId != 0) {
                    AppCompatResources.getDrawable(this, value.resourceId)
                } else {
                    ColorDrawable(value.data)
                }
            )
        }
        window.statusBarColor = themeColor(android.R.attr.statusBarColor)
        window.navigationBarColor = themeColor(android.R.attr.navigationBarColor)

        // The icon colours are a boolean theme flag rather than a window attribute, so they have to
        // be pushed to the insets controller or a dark skin keeps black status-bar icons.
        val lightBars = theme.resolveAttribute(
            android.R.attr.windowLightStatusBar,
            value,
            true
        ) && value.data != 0
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    // ---- three-page shell -------------------------------------------------

    /** Index of the page currently shown by [selectPage]. */
    private var currentPage = 0

    private fun setupTabs() {
        binding.tabGateway.setOnClickListener { selectPage(0) }
        binding.tabProvider.setOnClickListener { selectPage(1) }
        binding.tabRoute.setOnClickListener { selectPage(2) }
        selectPage(0, animate = false)
    }

    /**
     * Reveals one page and fades the others out. The slide distance is deliberately small: a full
     * page-width slide reads as navigation between screens, which this is not.
     */
    private fun selectPage(index: Int, animate: Boolean = true) {
        val pages = arrayOf(binding.pageGateway, binding.pageProviders, binding.pageRouting)
        if (index == currentPage && pages[index].visibility == View.VISIBLE) return

        pages.forEachIndexed { i, page ->
            if (i != index) {
                page.animate().cancel()
                page.visibility = View.GONE
            }
        }
        val incoming = pages[index]
        incoming.visibility = View.VISIBLE
        incoming.animate().cancel()
        if (animate) {
            incoming.alpha = 0f
            incoming.translationX = 24f * resources.displayMetrics.density *
                if (index > currentPage) 1f else -1f
            incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PAGE_SWITCH_MS)
                .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                .start()
        } else {
            incoming.alpha = 1f
            incoming.translationX = 0f
        }

        val accent = resolveThemeColor(R.attr.agwLink)
        val idle = resolveThemeColor(android.R.attr.textColorSecondary)
        val tabs = arrayOf(binding.tabGateway, binding.tabProvider, binding.tabRoute)
        val icons = arrayOf(binding.icTabGateway, binding.icTabProvider, binding.icTabRoute)
        val labels = arrayOf(binding.tvTabGateway, binding.tvTabProvider, binding.tvTabRoute)
        tabs.forEachIndexed { i, tab ->
            val active = i == index
            tab.isSelected = active
            icons[i].setColorFilter(if (active) accent else idle)
            labels[i].setTextColor(if (active) accent else idle)
        }
        currentPage = index
        // The reference only shows the add button while the provider page is open.
        binding.btnNewProvider.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    /** Capsule list: tap the name to switch, tap the edge to open the provider page, drag to sort. */
    private fun setupProviderList() {
        var touchHelper: ItemTouchHelper? = null
        providerAdapter = ProviderAdapter(
            onClick = { provider -> onProviderCapsuleClick(provider) },
            onMore = { provider, anchor -> showProviderMenu(provider, anchor) },
            onDragHandle = { holder -> touchHelper?.startDrag(holder) },
            onBindRow = { row, provider, isCurrent -> paintProviderCapsule(row, provider, isCurrent) }
        )
        binding.recyclerProviders.layoutManager = LinearLayoutManager(this)
        binding.recyclerProviders.adapter = providerAdapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            private var moved = false

            // Sorting is picked up from the capsule's more button, so a plain long press stays free
            // for the menu.
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    moved = false
                    viewHolder?.itemView?.alpha = 0.92f
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                providerAdapter.move(from, to)
                moved = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                // A drag that actually moved a row is the only thing that writes an order back.
                if (moved) viewModel.reorderProviders(providerAdapter.orderedIds())
                moved = false
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerProviders)
    }

    private fun onProviderCapsuleClick(provider: Provider) {
        if (provider.id == viewModel.uiState.value.config.currentProviderId) return
        viewModel.selectProvider(provider.id)
    }

    /**
     * The .menu panel of the reference board: 编辑配置 / 复制地址 / 删除. Sorting is the drag on the
     * more button, and switching is a plain tap on the pill, so the menu stays three entries long.
     */
    private fun showProviderMenu(provider: Provider, anchor: View) {
        GlassMenus.show(
            anchor,
            listOf(
                GlassMenus.Item(getString(R.string.provider_action_edit)) { openProviderDetail(provider) },
                GlassMenus.Item(getString(R.string.provider_action_copy)) { copyProviderUrl(provider) },
                GlassMenus.Item(getString(R.string.provider_action_delete), destructive = true) {
                    confirmDeleteProvider(provider.id, provider.name)
                }
            )
        )
    }

    /** 复制地址: the upstream URL lands on the clipboard; an empty URL says so instead of copying "". */
    private fun copyProviderUrl(provider: Provider) {
        val url = provider.upstreamUrl.trim()
        if (url.isEmpty()) {
            GlassDialogs.message(
                this,
                title = getString(R.string.provider_action_copy),
                message = getString(R.string.provider_copy_empty)
            )
            return
        }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("upstream", url))
        GlassDialogs.message(
            this,
            title = getString(R.string.provider_action_copy),
            message = url
        )
    }

    /** Swaps one provider with its neighbour and stores the whole order in a single write. */
    private fun moveProvider(id: String, target: Int) {
        val ids = viewModel.uiState.value.config.providers.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0 || target !in ids.indices) return
        ids.add(target, ids.removeAt(from))
        viewModel.reorderProviders(ids)
    }

    /**
     * One .cfg row per provider, drawn from the reference board: 14dp corners (11 sketch, 16 silk),
     * the active row gains an accent stripe, an accent border and the 使用中 chip, while the sketch
     * skin keeps its paper fill and ink outline instead of an accent wash.
     */
    private fun paintProviderCapsule(row: View, provider: Provider, isCurrent: Boolean) {
        val skin = skinPreferences.skin
        val accent = resolveThemeColor(android.R.attr.colorAccent)
        val surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        val border = resolveThemeColor(R.attr.agwCardBorder)
        val radius = when (skin) {
            Skin.SKETCH -> 11f
            Skin.SILK -> 16f
            else -> 14f
        }
        val fill = when {
            skin == Skin.SILK && isCurrent -> SILK_PILL_ON
            skin == Skin.SILK -> SILK_PILL
            skin == Skin.SKETCH && isCurrent -> SKETCH_ROW_ON
            // min / mnd only change the border and gain the stripe, as .cfg.on does in the board.
            else -> surface
        }
        val stroke = when {
            skin == Skin.SKETCH -> SketchSpec.INK
            skin == Skin.SILK && isCurrent -> SILK_PILL_ON_STROKE
            skin == Skin.SILK -> SILK_HAIRLINE
            isCurrent -> accent
            else -> border
        }
        val strokeDp = if (skin == Skin.SKETCH) 2.4f else 1f
        val base = roundedFill(fill, stroke, radius, strokeDp)
        // min / mnd / acr mark the active row with the 3dp stripe at the left edge, as the board's
        // .cfg.on::before does; the sketch skin deliberately drops it.
        row.background = if (isCurrent && skin != Skin.SKETCH) accentStripe(base, accent) else base

        val holder = row.tag
        if (holder !is ProviderAdapter.ProviderHolder) return
        val density = resources.displayMetrics.density
        holder.name.setTypeface(
            null,
            if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        holder.current.visibility = if (isCurrent) View.VISIBLE else View.GONE
        if (isCurrent) {
            if (skin == Skin.SKETCH) {
                holder.current.setTextColor(SketchSpec.INK)
                holder.current.background = roundedFill(SKETCH_CHIP, SketchSpec.INK, 7f, 1.8f)
            } else {
                holder.current.setTextColor(accent)
                holder.current.background =
                    roundedFill(accent and 0x00FFFFFF or (0x26 shl 24), 0, 7f, 0f)
            }
        }
        // The vector carries the dark silk ink; retint it so the button reads on every skin.
        holder.more.setColorFilter(resolveThemeColor(android.R.attr.textColorSecondary))
        holder.url.text = provider.upstreamUrl.ifBlank { getString(R.string.provider_url_missing) }
        holder.url.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary))
        row.elevation = 0f
    }

    /** Base surface plus the 3dp accent stripe the reference draws down the active row's left edge. */
    private fun accentStripe(base: android.graphics.drawable.GradientDrawable, accent: Int): android.graphics.drawable.LayerDrawable {
        val density = resources.displayMetrics.density
        val stripe = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent)
            cornerRadius = 1.5f * density
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(base, stripe))
        layers.setLayerSize(1, (3 * density).roundToInt(), -1)
        layers.setLayerInset(1, 0, (16 * density).roundToInt(), 0, (16 * density).roundToInt())
        return layers
    }

    /** Accent over surface, kept flat so a pill never turns into a bright slab on the dark skins. */
    private fun blendColor(accent: Int, base: Int, ratio: Float): Int {
        val r = (Color.red(accent) * ratio + Color.red(base) * (1 - ratio)).roundToInt()
        val g = (Color.green(accent) * ratio + Color.green(base) * (1 - ratio)).roundToInt()
        val b = (Color.blue(accent) * ratio + Color.blue(base) * (1 - ratio)).roundToInt()
        return Color.rgb(r, g, b)
    }

    /** Opens the editable page for one provider; back returns to the list. */
    private fun openProviderDetail(provider: Provider) {
        detailProviderId = provider.id
        binding.etProviderDetailName.setText(provider.name)
        binding.etProviderDetailUrl.setText(provider.upstreamUrl)
        binding.etProviderDetailKey.setText(provider.upstreamKey)
        applyKeyVisibility(false)
        // Deleting the last provider would leave the gateway without an upstream.
        binding.btnProviderDetailDelete.visibility =
            if (viewModel.uiState.value.config.providers.size > 1) View.VISIBLE else View.GONE
        // The page itself is transparent so the silk keeps showing; the list behind it has to go,
        // otherwise its pills read through the translucent fields.
        binding.layoutContentRoot.visibility = View.INVISIBLE
        binding.sheetProviderDetail.visibility = View.VISIBLE
        binding.sheetProviderDetail.alpha = 0f
        binding.sheetProviderDetail.animate().alpha(1f).setDuration(140).start()
    }

    private fun closeProviderDetail() {
        binding.layoutContentRoot.visibility = View.VISIBLE
        binding.sheetProviderDetail.animate().alpha(0f).setDuration(110)
            .withEndAction { binding.sheetProviderDetail.visibility = View.GONE }
            .start()
        detailProviderId = null
    }

    /** Writes the three edited fields back; blank input keeps the stored value. */
    private fun saveProviderDetail() {
        val id = detailProviderId ?: return
        val name = binding.etProviderDetailName.text?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) viewModel.renameProvider(id, name)
        viewModel.updateProviderUpstream(
            id,
            binding.etProviderDetailUrl.text?.toString()?.trim().orEmpty(),
            binding.etProviderDetailKey.text?.toString()?.trim().orEmpty()
        )
        closeProviderDetail()
    }

    /**
     * The stored key stays masked; the eye reveals it just for this visit, and the caret keeps its
     * place because the transformation swap would otherwise jump to the end.
     */
    private fun applyKeyVisibility(revealed: Boolean) {
        keyRevealed = revealed
        val field = binding.etProviderDetailKey
        val caret = field.selectionStart.coerceIn(0, field.text?.length ?: 0)
        field.transformationMethod =
            if (revealed) null else android.text.method.PasswordTransformationMethod.getInstance()
        field.setSelection(caret)
        binding.btnProviderDetailKeyToggle.setImageResource(
            if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye
        )
        binding.btnProviderDetailKeyToggle.contentDescription =
            getString(if (revealed) R.string.key_hide else R.string.key_show)
    }

    private fun setupProviderDetail() {
        binding.btnProviderDetailBack.setOnClickListener { closeProviderDetail() }
        binding.btnProviderDetailKeyToggle.setOnClickListener { applyKeyVisibility(!keyRevealed) }
        paintPrimaryButton(binding.btnProviderDetailSave, com.google.android.material.R.attr.colorPrimary)
        binding.btnProviderDetailSave.setOnClickListener { saveProviderDetail() }
        binding.btnProviderDetailDelete.setOnClickListener {
            val id = detailProviderId ?: return@setOnClickListener
            confirmDeleteProvider(id, binding.etProviderDetailName.text?.toString().orEmpty())
        }
    }

    private fun renderProviderList(state: UiState) {
        // Every provider lives in the list, as in the reference board: the active pill keeps its
        // 使用中 chip instead of being pulled out into a separate header card.
        providerAdapter.submit(state.config.providers, state.config.currentProviderId)
        binding.layoutProviderList.visibility =
            if (state.config.providers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteProvider(providerId: String, name: String) {
        GlassDialogs.message(
            activity = this,
            title = "删除供应商",
            message = "「$name」将从列表中移除。",
            confirmLabel = "删除",
            cancelLabel = "取消",
            destructive = true,
            onConfirm = { viewModel.deleteProvider(providerId) }
        )
    }

    private fun updateGuardSummary(state: UiState) {
        val guard = state.config.guard
        val level = getString(
            when (guard.antiRouteLevel) {
                AntiRouteLevel.OFF -> R.string.settings_guard_level_off
                AntiRouteLevel.WIRE -> R.string.settings_guard_level_wire
                AntiRouteLevel.STANDARD -> R.string.settings_guard_level_standard
                AntiRouteLevel.DEEP -> R.string.settings_guard_level_deep
            }
        )
        val report = when (guard.modelReport) {
            ModelReportMode.OFF -> "关闭"
            ModelReportMode.HEADERS -> "响应头"
            ModelReportMode.BODY -> "响应体"
            ModelReportMode.BOTH -> "响应头 + 响应体"
        }
        binding.tvGuardSummary.text = "防护强度：$level · 模型上报：$report"
        binding.tvRowGuardLevel.text = level
        binding.tvRowReport.text = report
    }


    private fun showCreateProviderDialog() {
        val suggested = "供应商 ${viewModel.uiState.value.config.providers.size + 1}"
        GlassDialogs.input(
            activity = this,
            title = "新建供应商",
            label = "名称",
            value = suggested,
            hint = "供应商名称",
            confirmLabel = "创建",
            validate = { if (it.isEmpty()) "名称不能为空" else null },
            onCommit = { name -> viewModel.createProvider(name) }
        )
    }

    private fun showRenameProviderDialog(providerId: String, currentName: String) {
        GlassDialogs.input(
            activity = this,
            title = "重命名供应商",
            label = "名称",
            value = currentName,
            confirmLabel = "保存",
            validate = { if (it.isEmpty()) "名称不能为空" else null },
            onCommit = { name -> viewModel.renameProvider(providerId, name) }
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
