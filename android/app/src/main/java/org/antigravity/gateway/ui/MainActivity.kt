package org.antigravity.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.antigravity.gateway.GatewayApplication
import org.antigravity.gateway.R
import org.antigravity.gateway.data.Skin
import org.antigravity.gateway.data.SkinPreferences
import org.antigravity.gateway.databinding.ActivityMainBinding
import org.antigravity.gateway.util.CrashReporter

class MainActivity : AppCompatActivity() {

    companion object {
        /** Debug-only intent extra used by the crash-recorder self test. */
        const val EXTRA_TEST_CRASH = "agw_test_crash"

        /** Heaviest weight the platform font picker will resolve; the sketch skin leans on it. */
        private const val HEAVY_WEIGHT = 900

        /** Holds the sketch stroke back until the window's enter animation has finished. */
        private const val REVEAL_HOLD_MS = 280L

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

    private var isInternalTextUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        skinPreferences = SkinPreferences(this)
        setTheme(skinPreferences.skin.themeRes)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTestResult.movementMethod = ScrollingMovementMethod()
        binding.btnViewCrashLog.setOnClickListener { showCrashLogDialog() }
        setupListeners()
        applySketchSurface()
        observeUiState()
        updateCrashLogEntry()
        handleIntent(intent)
        maybeTriggerTestCrash()
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
        binding.btnViewCrashLog.visibility = if (count > 0) View.VISIBLE else View.GONE
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
        // The dashboard is usable, so the startup crash counter can be reset.
        CrashReporter.markStartupHealthy(this)
        if (GatewayApplication.safeMode) {
            reportSafeMode()
        }
        viewModel.loadConfigAndNetwork()
        updateCrashLogEntry()
        // The silk skin is the only one backed by a live GL surface; it starts and stops with the
        // activity so the backdrop never animates behind a locked screen.
        if (skinPreferences.skin == Skin.SILK) {
            binding.silkBackdrop.startBackdrop()
            applySilkSurface()
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

        // Provider switcher & rename
        binding.layoutProviderSelect.setOnClickListener { showProviderDialog() }
        binding.btnSwitchProvider.setOnClickListener { showProviderDialog() }
        binding.btnRenameCurrentProvider.setOnClickListener {
            val current = viewModel.uiState.value.config.getCurrentProvider()
            if (current != null) {
                showRenameProviderDialog(current.id, current.name)
            }
        }
        binding.tvCurrentProvider.setOnLongClickListener {
            val current = viewModel.uiState.value.config.getCurrentProvider()
            if (current != null) {
                showRenameProviderDialog(current.id, current.name)
            }
            true
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
        binding.btnFetchModels.setOnClickListener {
            viewModel.fetchModels()
        }

        // Test Message
        binding.btnTestMessage.setOnClickListener {
            val customModel = binding.etTestModelId.text?.toString() ?: ""
            viewModel.testMessage(customModel)
        }
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

        // 1. Provider header
        binding.tvCurrentProvider.text = currentProvider?.name ?: "未选择供应商"

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
                binding.btnFetchModels.isEnabled = false
                binding.btnTestMessage.isEnabled = false
            }
            GatewayState.STARTING -> {
                binding.btnStartStop.isEnabled = false
                binding.btnStartStop.text = "正在启动..."
                binding.btnFetchModels.isEnabled = false
                binding.btnTestMessage.isEnabled = false
            }
            GatewayState.RUNNING -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = getString(R.string.stop_gateway)
                paintStartButton(com.google.android.material.R.attr.colorError)
                binding.btnFetchModels.isEnabled = !state.isTesting
                binding.btnTestMessage.isEnabled = !state.isTesting
            }
            GatewayState.STOPPING -> {
                binding.btnStartStop.isEnabled = false
                binding.btnStartStop.text = "正在停止..."
                binding.btnFetchModels.isEnabled = false
                binding.btnTestMessage.isEnabled = false
            }
            GatewayState.ERROR -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = "重试启动"
                paintStartButton(com.google.android.material.R.attr.colorPrimary)
                binding.btnFetchModels.isEnabled = false
                binding.btnTestMessage.isEnabled = false
            }
        }

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
    }

    private fun decorateSilk(view: View, density: Float) {
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
                    view.setTypeface(android.graphics.Typeface.create(view.typeface, HEAVY_WEIGHT, false))
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
        val button = binding.btnStartStop
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
            button.setBackgroundColor(themeColor(colorAttr))
        }
    }

    /** Resolves a theme attribute so dynamic colors follow the active skin. */
    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return 0
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun showStyleMenu() {
        if (stylePopup?.isShowing == true) return
        val popup = StyleMenuPopup(binding.btnStyle, skinPreferences.skin) { skin ->
            if (skin != skinPreferences.skin) {
                skinPreferences.skin = skin
                recreate()
            }
        }
        popup.setOnDismissListener {
            stylePopup = null
            binding.btnStyle.setOpen(false)
        }
        stylePopup = popup
        popup.show()
    }

    private fun showProviderDialog() {
        val state = viewModel.uiState.value
        if (state.gatewayState == GatewayState.RUNNING || state.gatewayState == GatewayState.STARTING) {
            Toast.makeText(this, "网关运行中，切换供应商需先停止", Toast.LENGTH_SHORT).show()
            return
        }

        val providers = state.config.providers
        val currentId = state.config.currentProviderId

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        var alertDialog: AlertDialog? = null

        for (p in providers) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.dialog_provider_item, container, false)
            val rb = itemView.findViewById<RadioButton>(R.id.rbSelected)
            val tvName = itemView.findViewById<TextView>(R.id.tvProviderName)
            val tvHost = itemView.findViewById<TextView>(R.id.tvProviderUrlHost)
            val btnRename = itemView.findViewById<View>(R.id.btnRenameProvider)
            val btnDelete = itemView.findViewById<View>(R.id.btnDeleteProvider)

            rb.isChecked = (p.id == currentId)
            tvName.text = p.name

            btnRename.setOnClickListener {
                showRenameProviderDialog(p.id, p.name)
                alertDialog?.dismiss()
            }
            val host = try {
                if (p.upstreamUrl.isNotEmpty()) Uri.parse(p.upstreamUrl).host ?: p.upstreamUrl else "未配置 URL"
            } catch (_: Exception) {
                p.upstreamUrl
            }
            tvHost.text = host

            if (providers.size > 1) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除供应商「${p.name}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            viewModel.deleteProvider(p.id)
                            alertDialog?.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            itemView.setOnClickListener {
                viewModel.selectProvider(p.id)
                alertDialog?.dismiss()
            }

            container.addView(itemView)
        }

        alertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择供应商")
            .setView(container)
            .setPositiveButton("新建供应商") { _, _ ->
                showCreateProviderDialog()
            }
            .setNegativeButton("关闭", null)
            .create()

        alertDialog.show()
    }

    private fun showCreateProviderDialog() {
        val input = EditText(this).apply {
            hint = "供应商名称 (如: 供应商 ${viewModel.uiState.value.config.providers.size + 1})"
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建供应商")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                viewModel.createProvider(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun showRenameProviderDialog(providerId: String, currentName: String) {
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("重命名供应商")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewModel.renameProvider(providerId, newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
