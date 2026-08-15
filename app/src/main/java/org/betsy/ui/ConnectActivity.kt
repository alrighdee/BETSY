package org.betsy.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.betsy.BuildConfig
import org.betsy.debug.CaptureLog
import org.betsy.debug.DEMO_DELAY
import org.betsy.debug.DemoMode
import org.betsy.debug.DemoScenario
import org.betsy.detect.VehicleDetector
import org.betsy.elm.ElmSession
import org.betsy.transport.BluetoothTransport
import org.betsy.transport.ElmTransport
import org.betsy.transport.ReplayTransport
import org.betsy.transport.TransportException
import org.betsy.transport.WifiTransport
import org.betsy.transport.awaitBlocking
import org.betsy.ui.connect.AdapterCandidate
import org.betsy.ui.connect.AdapterMemory
import org.betsy.ui.connect.ConnectPhase
import org.betsy.ui.connect.ConnectProgress
import org.betsy.ui.connect.ConnectScreen
import org.betsy.ui.connect.ConnectUiState
import org.betsy.ui.connect.Reachability
import org.betsy.ui.connect.Transport
import org.betsy.ui.connect.WifiEndpoint
import org.betsy.ui.theme.applyBetsyTheme
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connection screen: pick a transport, pick an adapter from the scanned list, connect, run the §1.1
 * session init + §3 vehicle detection, and launch the monitor.
 *
 * Plain android.app.Activity, no AndroidX; UI built programmatically in [ConnectScreen]. The four
 * phases the screen names map one-to-one onto the real work, so a failure reports which phase failed.
 */
class ConnectActivity :
    Activity(),
    ConnectScreen.Callbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val btPermissionCode = 1001

    private lateinit var screen: ConnectScreen
    private lateinit var memory: AdapterMemory

    private var transport: Transport = Transport.BLUETOOTH
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var candidates: List<AdapterCandidate> = emptyList()
    private var selectedId: String? = null
    private var scanning = false
    private var connecting = false
    private var reachability: Reachability = Reachability.UNKNOWN

    /** Live transport for the in-flight attempt; closing it is how Cancel unblocks a stuck read. */
    private var pendingTransport: ElmTransport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBetsyTheme()
        CaptureLog.start(applicationContext)
        CaptureLog.log("UI", "ConnectActivity created")
        memory = AdapterMemory(this)
        screen = ConnectScreen(this, this)
        screen.setWifiAddress("${WifiTransport.DEFAULT_HOST}:${WifiTransport.DEFAULT_PORT}")
        setContentView(screen)
        rescan()
        offerPendingCapture()
    }

    /**
     * A capture that failed to send is held on disk and offered again here, on the launch screen,
     * because resending it never involves the car: it is a POST of bytes already collected.
     *
     * This is what makes the failure message honest. Telling someone their scan is kept is only
     * true if something later gives it back to them, and the alternative is asking them to drive
     * out and read the car twice.
     */
    private fun offerPendingCapture() {
        PendingCaptureDialog(this).show()
    }

    // ── ConnectScreen.Callbacks ──

    override fun onTransportChanged(transport: Transport) {
        this.transport = transport
        rescan()
    }

    override fun onAdapterSelected(id: String) {
        selectedId = id
        render()
    }

    override fun onRescan() {
        rescan()
    }

    override fun onConnect() {
        val target = candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull() ?: return
        connecting = true
        screen.beginConnecting(target)
        render()
        startAttempt(target)
    }

    override fun onCancel() {
        pendingTransport?.close()
        pendingTransport = null
        connecting = false
        DemoMode.deactivate()
        render()
    }

    override fun onOpenBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun onWifiAddressChanged() {
        if (transport == Transport.WIFI) rescan()
    }

    // ── Scanning ──

    /**
     * Rebuilds the candidate list. Bluetooth enumerates bonded devices, which is instant; Wi-Fi has
     * no discovery protocol, so the configured endpoint is the one candidate and the scan is a real
     * TCP reachability probe against it.
     */
    private fun rescan() {
        if (transport == Transport.DEMO) {
            reachability = Reachability.UNKNOWN
            // A fixture is a script, not a device: no Bluetooth permission, no TCP probe. Each
            // scenario becomes one card, with a cached banner so it grades GOOD rather than
            // "Firmware unknown".
            candidates = DemoScenario.entries.map { demoCandidate(it) }
            selectedId = candidates.firstOrNull { it.lastUsed }?.id ?: candidates.firstOrNull()?.id
            scanning = false
            render()
            return
        }
        if (transport == Transport.BLUETOOTH) {
            reachability = Reachability.UNKNOWN
            if (!refreshBluetoothDevices()) return
            // Enumerating bonded devices returns instantly, so redrawing the identical list makes
            // Rescan look broken. Clear it and run the spinner briefly, then bring the list back:
            // the delay buys nothing technically, it just makes a real action legible.
            candidates = emptyList()
            scanning = true
            render()
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                candidates = bluetoothCandidates()
                selectedId = candidates.firstOrNull { it.lastUsed }?.id ?: candidates.firstOrNull()?.id
                scanning = false
                render()
            }, RESCAN_FEEDBACK_MS)
        } else {
            val endpoint = WifiEndpoint.parse(screen.wifiAddress())
            val id = "wifi:$endpoint"
            candidates =
                listOf(
                    AdapterCandidate(
                        id = id,
                        name = "Wi-Fi ELM327",
                        address = endpoint.toString(),
                        firmware = memory.firmware(id),
                        lastUsed = memory.lastUsedId == id,
                    ),
                )
            selectedId = id
            scanning = true
            reachability = Reachability.CHECKING
            render()
            probeReachability(endpoint)
        }
    }

    /**
     * Bonded devices as cards, the last one that worked first.
     *
     * A paired list is mostly speakers and headphones, so the single device the user came here for
     * sat below the fold while being silently preselected. The card already carries a "Last used"
     * badge, so the ordering explains itself.
     *
     * Both call sites go through here. They used to build the list separately, and only one of
     * them sorted.
     */
    private fun bluetoothCandidates(): List<AdapterCandidate> =
        pairedDevices
            .map { device -> bluetoothCandidate(device) }
            .sortedByDescending { it.lastUsed }

    private fun bluetoothCandidate(device: BluetoothDevice): AdapterCandidate {
        @Suppress("MissingPermission")
        val name = device.name ?: "Unnamed adapter"
        return AdapterCandidate(
            id = device.address,
            name = name,
            address = device.address,
            firmware = memory.firmware(device.address),
            lastUsed = memory.lastUsedId == device.address,
        )
    }

    private fun demoCandidate(scenario: DemoScenario): AdapterCandidate =
        AdapterCandidate(
            id = "demo:${scenario.name}",
            name = scenario.title,
            address = scenario.description,
            firmware = "ELM327 v2.2",
            lastUsed = memory.lastUsedId == "demo:${scenario.name}",
        )

    private fun probeReachability(endpoint: WifiEndpoint) {
        Thread {
            val reachable =
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), PROBE_TIMEOUT_MS)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            handler.post {
                scanning = false
                reachability = if (reachable) Reachability.REACHABLE else Reachability.UNREACHABLE
                render()
            }
        }.start()
    }

    // ── Connecting ──

    private fun startAttempt(target: AdapterCandidate) {
        val wifi = transport == Transport.WIFI
        val demo = transport == Transport.DEMO
        // A real connect ends any demo: the demo flag dies when its transport is replaced. The
        // demo branch re-activates it in openTransport(), so only the real path needs clearing.
        if (!demo) DemoMode.deactivate()
        val progress = ConnectProgress(wifi, linkLabel = if (demo) "Opening the demo session" else null)
        var phase = ConnectPhase.LINK

        Thread {
            try {
                phase = ConnectPhase.LINK
                val link = timed(progress, phase) { openTransport(target) }
                pendingTransport = link
                val session = ElmSession(link)

                phase = ConnectPhase.ADAPTER
                timed(progress, phase) {
                    awaitBlocking { session.reset() }
                    progress.adapterDetected(session.adapterBanner)
                }
                memory.rememberFirmware(target.id, session.adapterBanner)

                phase = ConnectPhase.HANDSHAKE
                timed(progress, phase) { awaitBlocking { session.configure() } }

                phase = ConnectPhase.VEHICLE
                val info = timed(progress, phase) { awaitBlocking { VehicleDetector.detect(session) } }

                if (!info.supported && !info.captureOnly) {
                    CaptureLog.log("UI", "${info.model.label} detected but unsupported")
                    DemoMode.deactivate()
                    handler.post {
                        Toast
                            .makeText(
                                this,
                                "${info.model.label} recognized but not yet supported in this build",
                                Toast.LENGTH_LONG,
                            ).show()
                        connecting = false
                        pendingTransport = null
                        render()
                    }
                    return@Thread
                }

                // A layout recognised but never decoded goes straight to the capture screen. Live
                // battery values are withheld because nothing has ever verified them for this car,
                // but codes and the raw tables are readable, and that capture is the only thing
                // that could make the layout supportable.
                if (info.captureOnly) {
                    CaptureLog.log("UI", "${info.model.label}: capture-only, routing to DTC screen")
                    SessionHolder.set(link, session, info)
                    handler.post {
                        pendingTransport = null
                        connecting = false
                        render()
                        Toast
                            .makeText(
                                this,
                                "Your car answers in a way BETSY has not seen before. Live battery " +
                                    "data is off, but sharing this scan is what would add support for it.",
                                Toast.LENGTH_LONG,
                            ).show()
                        startActivity(Intent(this, DtcActivity::class.java))
                    }
                    return@Thread
                }

                memory.lastUsedId = target.id
                SessionHolder.set(link, session, info)
                CaptureLog.log("UI", "session ready, launching monitor")
                handler.post {
                    pendingTransport = null
                    connecting = false
                    render()
                    startActivity(Intent(this, BatteryActivity::class.java))
                }
            } catch (e: Exception) {
                CaptureLog.logThrowable("UI", e)
                DemoMode.deactivate()
                progress.fail(phase)
                val snapshot = progress.snapshot()
                val reason =
                    when (e) {
                        is TransportException -> e.message ?: "transport failed"
                        else -> e.message ?: e.toString()
                    }
                handler.post {
                    pendingTransport?.close()
                    pendingTransport = null
                    screen.updateProgress(snapshot)
                    screen.showConnectError(reason)
                }
            }
        }.start()
    }

    /** Runs one phase, publishing its start and its real elapsed time to the panel. */
    private fun <T> timed(
        progress: ConnectProgress,
        phase: ConnectPhase,
        block: () -> T,
    ): T {
        progress.begin(phase)
        publish(progress)
        val startedAt = System.nanoTime()
        val result = block()
        progress.complete(phase, (System.nanoTime() - startedAt) / 1_000_000)
        publish(progress)
        return result
    }

    private fun publish(progress: ConnectProgress) {
        val snapshot = progress.snapshot()
        handler.post { screen.updateProgress(snapshot) }
    }

    private fun openTransport(target: AdapterCandidate): ElmTransport =
        when {
            transport == Transport.DEMO && BuildConfig.DEBUG -> {
                val scenario =
                    DemoScenario.byId(target.id)
                        ?: throw TransportException("unknown demo fixture ${target.id}")
                DemoMode.activate(scenario)
                CaptureLog.log("UI", "demo: ${scenario.title}")
                // A real link phase is a socket connect; the scripted one has no I/O, so a short
                // pause stands in for it and keeps the LINK row from reading "0 ms".
                Thread.sleep(DEMO_LINK_MS)
                ReplayTransport(scenario.script, delay = DEMO_DELAY)
            }
            transport == Transport.WIFI -> {
                val endpoint = WifiEndpoint.parse(screen.wifiAddress())
                CaptureLog.log("UI", "connecting: wifi ${endpoint.host}:${endpoint.port}")
                WifiTransport(endpoint.host, endpoint.port)
            }
            else -> {
                val device =
                    pairedDevices.firstOrNull { it.address == target.id }
                        ?: throw TransportException("adapter ${target.address} is no longer paired")
                CaptureLog.log("UI", "connecting: bluetooth ${target.address}")
                BluetoothTransport(device)
            }
        }

    // ── Bluetooth permission + enumeration ──

    /** Returns false when the permission request is in flight and the caller should stand down. */
    private fun refreshBluetoothDevices(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            pairedDevices = emptyList()
            return true
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), btPermissionCode)
            return false
        }
        @Suppress("MissingPermission")
        pairedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != btPermissionCode) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            @Suppress("MissingPermission")
            pairedDevices = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        }
        candidates = bluetoothCandidates()
        selectedId = candidates.firstOrNull { it.lastUsed }?.id ?: candidates.firstOrNull()?.id
        scanning = false
        render()
    }

    private fun render() {
        screen.render(
            ConnectUiState(
                transport = transport,
                candidates = candidates,
                selectedId = selectedId,
                scanning = scanning,
                connecting = connecting,
                reachability = reachability,
            ),
        )
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 1200

        /** Long enough for the spinner to register as a scan, short enough not to feel stalled. */
        const val RESCAN_FEEDBACK_MS = 450L

        /** Stands in for a demo link establishment, which has no real socket to connect. */
        const val DEMO_LINK_MS = 250L
    }
}
