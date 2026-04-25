package com.photoflowmobile.app.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

/**
 * Vendor-agnostic tethered-DSLR manager.
 *
 * Flow:
 *   1. Any PTP-class USB device attaches → request permission
 *   2. Select VendorAdapter by VID (Canon/Nikon/Generic)
 *   3. Open standard PTP session (PtpCore)
 *   4. Adapter.initialize() — vendor-specific unlock (Canon SetRemoteMode etc.)
 *   5. Poll loop: adapter.pollNewImageHandles() → GetObjectInfo → GetObject → deliver
 *   6. Fallback: every FALLBACK_POLL_MS call GetObjectHandles and diff against known set
 *
 * The diff-poll fallback is the safety net for cameras whose event delivery is unreliable
 * or whose adapter isn't aware of the specific vendor. It guarantees images are imported
 * within FALLBACK_POLL_MS of being shot, on any PTP-conformant camera.
 */
class MtpCameraManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onImageReceived: suspend (filename: String, data: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "PhotoFlow/Tether"
        private const val PIPELINE = "PhotoFlow/Pipeline"
        private const val ACTION_USB_PERMISSION = "com.photoflowmobile.USB_PERMISSION"
        private const val EVENT_POLL_MS    = 500L   // adapter event poll cadence
        private const val FALLBACK_POLL_MS = 3_000L // object-handle diff fallback cadence
        private const val HEARTBEAT_MS     = 30_000L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _status = MutableStateFlow(TetheredStatus())
    val status: StateFlow<TetheredStatus> = _status

    // Kept for API compatibility with MainViewModel/MainScreen — always null since no vendor
    // supplies live view through this pipeline.
    val liveViewFrame: StateFlow<ByteArray?> = MutableStateFlow(null)

    @Volatile private var core: PtpCore? = null
    @Volatile private var adapter: VendorAdapter? = null
    @Volatile private var ptpConn: PtpConnection? = null
    @Volatile private var isConnecting = false
    private var pollingJob: Job? = null

    private val connectionMutex = Mutex()

    // Every bulkTransfer on a UsbDeviceConnection must happen on the thread that set up the
    // connection — on Samsung's USB stack, crossing threads breaks subsequent transfers with
    // -1 even though the connection looks alive. Pin all USB I/O to one dedicated thread so
    // openConnection + the poll loop always share it.
    private val usbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhotoFlow-USB").apply { isDaemon = true }
    }
    private val usbDispatcher = usbExecutor.asCoroutineDispatcher()

    // ── Permission receiver ───────────────────────────────────────────────────

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = extractDevice(intent) ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                Log.d(TAG, "Permission granted for ${device.productName}")
                scope.launch(usbDispatcher) { openConnection(device) }
            } else {
                synchronized(this@MtpCameraManager) { isConnecting = false }
                Log.w(TAG, "Permission denied for ${device.productName}")
                _status.value = TetheredStatus(TetheredState.ERROR, message = "USB permission denied")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Scan for any already-connected PTP-class device (not just Canon)
        usbManager.deviceList.values
            .firstOrNull { isPtpDevice(it) }
            ?.let { connectOrRequestPermission(it) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun onDeviceAttached(device: UsbDevice) {
        Log.i(PIPELINE, "usb attached: '${device.productName}' " +
                "VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} " +
                "ptp=${isPtpDevice(device)}")
        if (isPtpDevice(device)) connectOrRequestPermission(device)
    }

    fun onDeviceDetached(device: UsbDevice) {
        Log.i(PIPELINE, "usb detached: '${device.productName}' " +
                "VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
        if (isPtpDevice(device)) closeConnection()
    }

    fun release() {
        closeConnection()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        try { usbExecutor.shutdown() } catch (_: Exception) {}
    }

    // ── Device identification ─────────────────────────────────────────────────

    /**
     * A USB device is "PTP" if any interface advertises class 6 (Still Image).
     * This covers every tethering-capable DSLR — Canon, Nikon, Sony, Fuji, Panasonic,
     * Olympus/OM, Pentax — without hard-coding vendor IDs.
     */
    private fun isPtpDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true
            }
        }
        return false
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    private fun connectOrRequestPermission(device: UsbDevice) {
        synchronized(this) {
            if (core != null || isConnecting) {
                Log.d(TAG, "Already connected/connecting — ignoring duplicate attach signal")
                return
            }
            isConnecting = true
        }
        if (usbManager.hasPermission(device)) {
            scope.launch(usbDispatcher) { openConnection(device) }
        } else {
            _status.value = TetheredStatus(TetheredState.CONNECTING, message = "Requesting USB access…")
            Log.d(TAG, "Requesting permission for ${device.productName} " +
                    "VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
        }
    }

    private suspend fun openConnection(device: UsbDevice) = connectionMutex.withLock {
        // A second caller waits here until the first finishes. After that, if the first
        // succeeded, core is non-null and we must not claim the interface a second time —
        // which would steal the USB handle from the running session and stall the endpoint.
        if (core != null) {
            Log.d(TAG, "openConnection: already connected — duplicate call ignored")
            return@withLock
        }

        val vendor = VendorAdapter.forVendor(device.vendorId)
        Log.i(TAG, "Opening PTP session with ${device.productName} " +
                "VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} " +
                "adapter=${vendor.name}")
        _status.value = TetheredStatus(TetheredState.CONNECTING, message = "Connecting…")

        try {
            val usbConn = usbManager.openDevice(device)
            if (usbConn == null) {
                Log.e(TAG, "openDevice returned null")
                _status.value = TetheredStatus(TetheredState.ERROR, message = "Failed to open USB device")
                synchronized(this) { isConnecting = false }
                return
            }

            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

            if (iface == null) {
                usbConn.close()
                Log.e(TAG, "No PTP interface (class 6) found on device")
                _status.value = TetheredStatus(TetheredState.ERROR, message = "Camera PTP interface not found")
                synchronized(this) { isConnecting = false }
                return
            }

            val conn = PtpConnection(usbConn, iface)
            val ptpCore = PtpCore(conn)

            // OpenSession, tolerant of "already open" and retrying once with a clean close first.
            if (!openSessionWithRetry(ptpCore)) {
                conn.release(); usbConn.close()
                _status.value = TetheredStatus(TetheredState.ERROR,
                    message = "Camera refused PTP session — try unplugging and reconnecting")
                synchronized(this) { isConnecting = false }
                return
            }

            if (!vendor.initialize(ptpCore, conn)) {
                Log.w(TAG, "Vendor initialize() reported failure — continuing anyway")
            }

            ptpConn = conn
            core    = ptpCore
            adapter = vendor

            val model = device.productName ?: vendor.name
            Log.i(TAG, "Connected: $model (adapter=${vendor.name})")
            Log.i(PIPELINE, "connected: '$model' adapter=${vendor.name} interruptEvents=${conn.hasInterruptEvents}")
            _status.value = TetheredStatus(TetheredState.CONNECTED, cameraModel = model)

            startPolling(ptpCore, conn, vendor)

        } catch (e: Exception) {
            Log.e(TAG, "openConnection threw", e)
            _status.value = TetheredStatus(TetheredState.ERROR,
                message = "Connection error: ${e.message?.take(80) ?: e.javaClass.simpleName}")
            synchronized(this) { isConnecting = false }
        }
    }

    /**
     * Open session, treating SESSION_ALREADY_OPEN as success. If we get any other error,
     * send CloseSession once to clear any stale state and retry.
     */
    private fun openSessionWithRetry(core: PtpCore): Boolean {
        val first = core.openSession()
        if (first == null) {
            Log.w(TAG, "openSession: null response on first attempt")
        } else if (first.isOk || first.code == PtpConnection.RESPONSE_SESSION_ALREADY_OPEN) {
            return true
        } else {
            Log.w(TAG, "openSession first attempt: response=${first.hex}")
        }

        // Clear any leftover session state and try again
        try { core.closeSession() } catch (_: Exception) {}
        Thread.sleep(200)

        val second = core.openSession()
        if (second == null) {
            Log.e(TAG, "openSession: null response on retry")
            return false
        }
        if (second.isOk || second.code == PtpConnection.RESPONSE_SESSION_ALREADY_OPEN) return true
        Log.e(TAG, "openSession retry: response=${second.hex}")
        return false
    }

    private fun closeConnection() {
        pollingJob?.cancel()
        pollingJob = null
        val a = adapter; val c = core; val conn = ptpConn
        if (a != null && c != null && conn != null) {
            try { a.shutdown(c, conn) } catch (_: Exception) {}
        }
        try { c?.closeSession() } catch (_: Exception) {}
        try { conn?.release() } catch (_: Exception) {}
        core    = null
        adapter = null
        ptpConn = null
        synchronized(this) { isConnecting = false }
        _status.value = TetheredStatus(TetheredState.DISCONNECTED)
        Log.d(TAG, "Disconnected")
    }

    // ── Polling loop ──────────────────────────────────────────────────────────

    private fun startPolling(core: PtpCore, conn: PtpConnection, adapter: VendorAdapter) {
        pollingJob = scope.launch(usbDispatcher) {
            Log.d(TAG, "Poll loop started (adapter=${adapter.name})")

            // Seed "known handles" with whatever's already on the card so we only download
            // images shot after connection. Without this, every reconnect would re-pull
            // every file on the SD card.
            val knownHandles = HashSet<Int>()
            try {
                core.getImageObjectHandles().forEach { knownHandles.add(it) }
                Log.i(PIPELINE, "seeded: ${knownHandles.size} pre-existing objects on card " +
                        "(only new shots will be imported)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not seed known handles", e)
            }

            var lastFallbackPoll = System.currentTimeMillis()
            var lastHeartbeat    = System.currentTimeMillis()
            var pollCount        = 0
            var deliveredCount   = 0

            while (isActive) {
                try {
                    pollCount++

                    // 1. Fast path: adapter-specific event/interrupt polling
                    val fromAdapter = adapter.pollNewImageHandles(core, conn)
                    if (fromAdapter.isNotEmpty()) {
                        Log.i(PIPELINE, "event: ${adapter.name} reported ${fromAdapter.size} new handle(s): " +
                                fromAdapter.joinToString { "0x${it.toString(16)}" })
                    }
                    for (handle in fromAdapter) {
                        if (knownHandles.add(handle)) {
                            if (downloadAndDeliver(core, handle)) deliveredCount++
                        } else {
                            Log.d(PIPELINE, "skip: handle=0x${handle.toString(16)} already known")
                        }
                    }

                    // 2. Fallback: diff-poll GetObjectHandles on a slower cadence
                    val now = System.currentTimeMillis()
                    if (now - lastFallbackPoll >= FALLBACK_POLL_MS) {
                        lastFallbackPoll = now
                        val current = core.getImageObjectHandles()
                        val newOnes = current.filter { knownHandles.add(it) }
                        if (newOnes.isNotEmpty()) {
                            Log.i(PIPELINE, "fallback-poll: ${newOnes.size} new handle(s) missed by ${adapter.name}: " +
                                    newOnes.joinToString { "0x${it.toString(16)}" })
                            for (handle in newOnes) {
                                if (downloadAndDeliver(core, handle)) deliveredCount++
                            }
                        }
                    }

                    // 3. Heartbeat: confirm loop is alive even when nothing's happening
                    if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        lastHeartbeat = now
                        Log.i(PIPELINE, "heartbeat: adapter=${adapter.name} polls=$pollCount " +
                                "delivered=$deliveredCount known=${knownHandles.size}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll loop error", e)
                    Log.e(PIPELINE, "poll-loop FAILED after $pollCount polls / $deliveredCount delivered", e)
                    if (isActive) {
                        _status.value = TetheredStatus(TetheredState.ERROR,
                            message = "Camera lost: ${e.message?.take(80) ?: "unknown error"}")
                    }
                    break
                }
                delay(EVENT_POLL_MS)
            }
            Log.d(TAG, "Poll loop exited")
        }
    }

    /** @return true if an image was successfully delivered to onImageReceived. */
    private suspend fun downloadAndDeliver(core: PtpCore, handle: Int): Boolean {
        val handleHex = "0x${handle.toString(16)}"
        val tInfo = System.currentTimeMillis()
        val info = try {
            core.getObjectInfo(handle)
        } catch (e: Exception) {
            Log.e(PIPELINE, "getObjectInfo($handleHex) threw", e); return false
        }
        if (info == null) {
            Log.w(PIPELINE, "getObjectInfo($handleHex) returned null")
            return false
        }
        Log.d(PIPELINE, "info: handle=$handleHex name='${info.filename}' " +
                "format=0x${info.format.toString(16)} size=${info.fileSize}B " +
                "(${System.currentTimeMillis() - tInfo}ms)")

        if (!info.isImage) {
            Log.i(PIPELINE, "skip non-image: '${info.filename}' format=0x${info.format.toString(16)}")
            return false
        }

        val tDown = System.currentTimeMillis()
        val bytes = try {
            core.getObject(handle)
        } catch (e: Exception) {
            Log.e(PIPELINE, "getObject($handleHex '${info.filename}') threw", e); return false
        }
        if (bytes == null || bytes.isEmpty()) {
            Log.e(PIPELINE, "getObject($handleHex '${info.filename}') returned empty")
            return false
        }
        val downMs = System.currentTimeMillis() - tDown
        val rateKbps = if (downMs > 0) (bytes.size.toLong() * 8 / downMs) else 0
        Log.i(PIPELINE, "downloaded: '${info.filename}' ${bytes.size}B in ${downMs}ms (${rateKbps}kbps)")

        return try {
            onImageReceived(info.filename, bytes)
            true
        } catch (e: Exception) {
            Log.e(PIPELINE, "onImageReceived threw for '${info.filename}'", e)
            false
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

// ── Models ────────────────────────────────────────────────────────────────────

enum class TetheredState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class TetheredStatus(
    val state: TetheredState = TetheredState.DISCONNECTED,
    val cameraModel: String? = null,
    val message: String? = null
)
