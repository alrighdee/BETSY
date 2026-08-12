package org.betsy.ui.connect

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/** Whether the configured Wi-Fi endpoint answered a TCP probe. */
enum class Reachability {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
}

/** Everything the connect screen renders from. */
data class ConnectUiState(
    val transport: Transport,
    val candidates: List<AdapterCandidate>,
    val selectedId: String?,
    val scanning: Boolean,
    val connecting: Boolean,
    val reachability: Reachability,
)

/**
 * The redesigned connect screen. Scans on open and lists what it finds as tappable cards; the address
 * field only appears for Wi-Fi, where it is the only way to reach the adapter.
 */
class ConnectScreen(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {
    interface Callbacks {
        fun onTransportChanged(transport: Transport)

        fun onAdapterSelected(id: String)

        fun onRescan()

        fun onConnect()

        fun onCancel()

        fun onOpenBluetoothSettings()

        fun onWifiAddressChanged()
    }

    private val transportSelect = TransportSelect(context) { callbacks.onTransportChanged(it) }
    private val wifiSection: LinearLayout
    private val addressField: EditText
    private val reachDot: View
    private val reachLabel: TextView
    private val listSection: LinearLayout
    private val listTitle: TextView

    /**
     * Sits under the list head. Android hands back every bonded device, and there is no reliable
     * way to tell an ELM327 from a pair of headphones: the class-of-device field is routinely wrong
     * on cheap clones, the adapter this was developed against reports itself as a keyboard. So the
     * list stays honest about what it is showing rather than filtering on a signal that lies.
     */
    private val listHint: TextView
    private val scanIndicator: LinearLayout
    private val cardHolder: LinearLayout
    private val emptyPanel: EmptyStatePanel
    private val connectingPanel: ConnectingPanel
    private val connectButton: TextView
    private var radar: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(DesignTokens.GRAY_1)

        addView(buildHeader(), LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column =
            LinearLayout(context).apply {
                orientation = VERTICAL
                val h = Surfaces.dp(context, 20f)
                setPadding(h, Surfaces.dp(context, 8f), h, h)
            }

        // Label and control share one row, so the two read as a single sentence: the dropdown
        // already says Bluetooth or Wi-Fi, and the label only has to name what is being chosen.
        val transportRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        transportRow.addView(
            TextStyles.sectionLabel(context, "Connect to dongle via"),
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = Surfaces.dp(context, 12f)
            },
        )
        transportRow.addView(
            transportSelect,
            LayoutParams(0, Surfaces.dp(context, 54f), 1f),
        )
        column.addView(transportRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addressField = buildAddressField()
        reachDot = View(context)
        reachLabel = TextStyles.body(context, "", DesignTokens.TEXT_1, DesignTokens.GRAY_10)
        wifiSection = buildWifiSection()
        column.addView(
            wifiSection,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 20f) },
        )

        listTitle = TextStyles.sectionLabel(context, "Paired devices")
        listHint = TextStyles.body(context, "", DesignTokens.TEXT_2, DesignTokens.GRAY_11)
        scanIndicator = buildScanIndicator()
        cardHolder = LinearLayout(context).apply { orientation = VERTICAL }
        listSection = buildListSection()
        column.addView(
            listSection,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 20f) },
        )

        emptyPanel =
            EmptyStatePanel(
                context,
                onOpenBluetoothSettings = { callbacks.onOpenBluetoothSettings() },
                onUseWifi = {
                    transportSelect.select(Transport.WIFI)
                    callbacks.onTransportChanged(Transport.WIFI)
                },
            )
        column.addView(
            emptyPanel,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 20f) },
        )

        connectingPanel = ConnectingPanel(context) { callbacks.onCancel() }
        column.addView(
            connectingPanel,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 20f) },
        )

        scroll.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(scroll, LayoutParams(MATCH_PARENT, 0, 1f))

        connectButton =
            TextStyles.body(context, "Connect", 17f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background =
                    Surfaces.rounded(
                        context,
                        DesignTokens.BRAND_SOLID,
                        DesignTokens.RADIUS_CARD,
                        DesignTokens.BRAND_SOLID,
                    )
                setOnClickListener { callbacks.onConnect() }
            }
        Surfaces.raise(connectButton, 8f)
        val gutter = Surfaces.dp(context, 20f)
        addView(
            connectButton,
            LayoutParams(MATCH_PARENT, Surfaces.dp(context, 58f)).apply {
                leftMargin = gutter
                rightMargin = gutter
                bottomMargin = gutter
            },
        )
    }

    /**
     * Full-bleed logo. The product tagline lives in the artwork; only a small build label
     * (version · git) is overlaid so an installed APK can be identified.
     */
    private fun buildHeader(): View = LogoHeaderView(context)

    private fun buildAddressField(): EditText =
        EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.0.10:35000"
            textSize = DesignTokens.TEXT_4
            typeface = Typeface.MONOSPACE
            setTextColor(DesignTokens.GRAY_12)
            setHintTextColor(DesignTokens.GRAY_9)
            background = null
            setPadding(0, 0, 0, 0)
            setOnFocusChangeListener { _, focused ->
                if (!focused) callbacks.onWifiAddressChanged()
            }
        }

    private fun buildWifiSection(): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextStyles.sectionLabel(context, "Adapter address"))

            val row =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Surfaces.dp(context, 15f), 0, Surfaces.dp(context, 15f), 0)
                    background =
                        Surfaces.rounded(context, DesignTokens.GRAY_3, DesignTokens.RADIUS_4, DesignTokens.ghostBorder, 1f)
                }
            row.addView(addressField, LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(
                reachDot,
                LayoutParams(Surfaces.dp(context, 6f), Surfaces.dp(context, 6f)).apply {
                    rightMargin = Surfaces.dp(context, 6f)
                },
            )
            row.addView(reachLabel)
            addView(
                row,
                LayoutParams(MATCH_PARENT, Surfaces.dp(context, 52f)).apply {
                    topMargin = Surfaces.dp(context, 9f)
                },
            )

            addView(
                TextStyles.body(
                    context,
                    "Join the adapter's own Wi-Fi network first \u2014 most dongles keep this address.",
                    11f,
                    DesignTokens.GRAY_10,
                ),
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 8f) },
            )
        }

    private fun buildListSection(): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            val titleRow =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            titleRow.addView(listTitle)
            titleRow.addView(
                scanIndicator,
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = Surfaces.dp(context, 8f) },
            )
            titleRow.addView(View(context), LayoutParams(0, 1, 1f))

            val rescan =
                TextStyles.body(context, "Rescan", DesignTokens.TEXT_1, DesignTokens.BRAND_SOLID, bold = true).apply {
                    gravity = Gravity.CENTER
                    val h = Surfaces.dp(context, 13f)
                    setPadding(h, 0, h, 0)
                    background =
                        Surfaces.rounded(
                            context,
                            DesignTokens.badgeFillSelected,
                            DesignTokens.RADIUS_4,
                            DesignTokens.cardBorder,
                        )
                    setOnClickListener { callbacks.onRescan() }
                }
            titleRow.addView(rescan, LayoutParams(WRAP_CONTENT, Surfaces.dp(context, 30f)))
            addView(titleRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(listHint)

            addView(
                cardHolder,
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 11f) },
            )
        }

    /** The mockup's radar ping: a solid cyan dot with an expanding, fading ring behind it. */
    private fun buildScanIndicator(): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val size = Surfaces.dp(context, 8f)
            val pulse = View(context).apply { background = Surfaces.circle(context, DesignTokens.BRAND_SOLID) }
            val frame =
                FrameLayout(context).apply {
                    addView(
                        View(context).apply { background = Surfaces.circle(context, DesignTokens.BRAND_SOLID) },
                        FrameLayout.LayoutParams(size, size),
                    )
                    addView(pulse, FrameLayout.LayoutParams(size, size))
                }
            addView(frame, LayoutParams(size, size))
            addView(
                TextStyles.body(context, "scanning", DesignTokens.TEXT_1, DesignTokens.BRAND_SOLID),
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = Surfaces.dp(context, 6f) },
            )
            radar =
                ObjectAnimator
                    .ofPropertyValuesHolder(
                        pulse,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.55f, 1.5f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.55f, 1.5f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f),
                    ).apply {
                        duration = RADAR_MS
                        repeatCount = ValueAnimator.INFINITE
                    }
        }

    fun render(state: ConnectUiState) {
        val wifi = state.transport == Transport.WIFI
        transportSelect.select(state.transport)

        // A scan in progress is not an empty result. Without the scanning term, clearing the list
        // to rescan would flash "no adapters found" at someone who is mid-search.
        val empty = !wifi && state.candidates.isEmpty() && !state.connecting && !state.scanning
        wifiSection.visibility = if (wifi && !state.connecting) VISIBLE else GONE
        listSection.visibility = if (!state.connecting && !empty) VISIBLE else GONE
        emptyPanel.visibility = if (empty) VISIBLE else GONE
        connectingPanel.visibility = if (state.connecting) VISIBLE else GONE
        // The mockup shows the CTA in every non-connecting state, but with nothing to connect to it is
        // a dead control competing with the empty state's own primary action, so it is hidden there.
        connectButton.visibility = if (!state.connecting && state.candidates.isNotEmpty()) VISIBLE else GONE

        listTitle.text = if (wifi) "On this network" else "Paired devices"
        listHint.text =
            if (wifi) {
                "Anything answering on the adapter network."
            } else {
                "Select your adapter."
            }
        renderScanning(state.scanning)
        renderReachability(state.reachability)
        renderCards(state, wifi)
    }

    private fun renderScanning(scanning: Boolean) {
        scanIndicator.visibility = if (scanning) VISIBLE else GONE
        if (scanning) {
            if (radar?.isRunning != true) radar?.start()
        } else {
            radar?.cancel()
        }
    }

    private fun renderReachability(reachability: Reachability) {
        val (color, label) =
            when (reachability) {
                Reachability.REACHABLE -> DesignTokens.GREEN_TEXT to "reachable"
                Reachability.UNREACHABLE -> DesignTokens.RED_TEXT to "no answer"
                Reachability.CHECKING -> DesignTokens.GRAY_10 to "checking"
                Reachability.UNKNOWN -> Color.TRANSPARENT to ""
            }
        reachDot.background = Surfaces.circle(context, if (label.isEmpty()) Color.TRANSPARENT else color)
        reachLabel.text = label
        reachLabel.setTextColor(color)
    }

    private fun renderCards(
        state: ConnectUiState,
        wifi: Boolean,
    ) {
        while (cardHolder.childCount > state.candidates.size) {
            cardHolder.removeViewAt(cardHolder.childCount - 1)
        }
        while (cardHolder.childCount < state.candidates.size) {
            cardHolder.addView(
                AdapterCardView(context),
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = if (cardHolder.childCount == 0) 0 else Surfaces.dp(context, 11f)
                },
            )
        }
        state.candidates.forEachIndexed { index, candidate ->
            val card = cardHolder.getChildAt(index) as AdapterCardView
            card.bind(candidate, candidate.id == state.selectedId, wifi)
            card.setOnClickListener { callbacks.onAdapterSelected(candidate.id) }
        }
    }

    fun wifiAddress(): String = addressField.text.toString()

    fun setWifiAddress(text: String) {
        addressField.setText(text)
    }

    fun beginConnecting(target: AdapterCandidate) {
        connectingPanel.clearError()
        connectingPanel.bindTarget(target)
    }

    fun updateProgress(snapshot: ConnectSnapshot) {
        connectingPanel.update(snapshot)
    }

    fun showConnectError(message: String) {
        connectingPanel.showError(message)
    }

    private companion object {
        const val RADAR_MS = 1800L
    }
}
