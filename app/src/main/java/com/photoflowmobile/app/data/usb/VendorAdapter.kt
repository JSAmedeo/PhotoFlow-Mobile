package com.photoflowmobile.app.data.usb

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-vendor hook points around the standard PTP session.
 *
 * `MtpCameraManager` drives the lifecycle and calls these in order:
 *   initialize() → (loop) pollNewImageHandles() → shutdown()
 *
 * Adapters exist because:
 *   - Canon EOS locks the shutter button unless SetRemoteMode(1) is sent after OpenSession
 *   - Canon delivers new-image events via polled GetEvent (0x9127), not the interrupt endpoint
 *   - Nikon/Sony/Fuji/Panasonic generally use the standard interrupt-endpoint event channel
 *
 * For unknown vendors, GenericPtpAdapter relies on standard PTP alone — which works on
 * the vast majority of DSLRs for post-capture transfer even if tethered-shutter behavior varies.
 */
interface VendorAdapter {
    val name: String

    /** Called after OpenSession succeeds. Return false only for fatal init errors. */
    fun initialize(core: PtpCore, conn: PtpConnection): Boolean = true

    /**
     * Return the list of new image object handles that are ready to download.
     * Called on a ~500 ms cadence by the manager.
     *
     * Implementations may:
     *   - drain the standard PTP interrupt endpoint for ObjectAdded events
     *   - poll a vendor-specific event opcode (Canon GetEvent)
     *   - return empty (manager will fall back to GetObjectHandles diff polling)
     */
    fun pollNewImageHandles(core: PtpCore, conn: PtpConnection): List<Int> = emptyList()

    /** Called before CloseSession. Restore camera to non-tethered state if needed. */
    fun shutdown(core: PtpCore, conn: PtpConnection) {}

    companion object {
        private const val CANON = 0x04A9
        private const val NIKON = 0x04B0
        private const val SONY  = 0x054C
        private const val FUJI  = 0x04CB
        private const val PANASONIC = 0x04DA
        private const val OLYMPUS = 0x07B4
        private const val PENTAX  = 0x0A17

        fun forVendor(vendorId: Int): VendorAdapter = when (vendorId) {
            CANON -> CanonEosAdapter()
            NIKON -> NikonAdapter()
            else  -> GenericPtpAdapter()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic adapter — standard PTP + interrupt-endpoint events
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Works on any PTP-conformant camera. No vendor-specific init. Drains standard
 * ObjectAdded events from the USB interrupt endpoint on each poll.
 */
class GenericPtpAdapter : VendorAdapter {
    override val name = "Generic PTP"

    override fun pollNewImageHandles(core: PtpCore, conn: PtpConnection): List<Int> {
        if (!conn.hasInterruptEvents) return emptyList()
        val handles = mutableListOf<Int>()
        // Drain up to 16 pending events per poll — each readInterruptEvent is short-timeout
        repeat(16) {
            val ev = conn.readInterruptEvent() ?: return@repeat
            if (ev.code == PtpCore.EV_OBJECT_ADDED) {
                ev.params.firstOrNull()?.let { handles.add(it) }
            }
        }
        return handles
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Canon EOS adapter — 0x91xx extension opcodes + polled GetEvent
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Canon EOS cameras use extension opcodes. Specifically:
 *  - SetRemoteMode(1)  — keeps physical shutter active while tethered (EOS Utility sequence)
 *  - SetEventMode(1)   — enables EOS event delivery over GetEvent
 *  - GetEvent (0x9127) — polled; returns a list of EOS_EV_* records including OBJECT_ADDED
 *
 * Canon does NOT use the standard interrupt endpoint for events, which is why we poll.
 */
class CanonEosAdapter : VendorAdapter {
    override val name = "Canon EOS"

    companion object {
        private const val TAG = "PhotoFlow/Canon"
        private const val PIPELINE = "PhotoFlow/Pipeline"
        private const val OP_EOS_SET_REMOTE_MODE = 0x9114
        private const val OP_EOS_SET_EVENT_MODE  = 0x9115
        private const val OP_EOS_GET_EVENT       = 0x9127
        const val EOS_EV_OBJECT_ADDED = 0xC101
        const val EOS_EV_SHUTDOWN     = 0xC18A
    }

    override fun initialize(core: PtpCore, conn: PtpConnection): Boolean {
        val remote = sendAndLog(conn, OP_EOS_SET_REMOTE_MODE, listOf(1), "SetRemoteMode(1)")
        val event  = sendAndLog(conn, OP_EOS_SET_EVENT_MODE, listOf(1), "SetEventMode(1)")
        Log.i(PIPELINE, "canon-init: SetRemoteMode=${remote?.hex ?: "null"} " +
                "SetEventMode=${event?.hex ?: "null"} " +
                (if (remote?.isOk == true && event?.isOk == true) "(shutter unlocked)"
                 else "(WARNING: shutter may be locked)"))
        return true
    }

    override fun pollNewImageHandles(core: PtpCore, conn: PtpConnection): List<Int> {
        val txn = conn.sendCommand(OP_EOS_GET_EVENT)
        if (txn < 0) return emptyList()
        val (data, resp) = conn.readDataAndResponse() ?: return emptyList()
        if (!resp.isOk || data.size < 8) return emptyList()
        return parseCanonEventList(data)
    }

    override fun shutdown(core: PtpCore, conn: PtpConnection) {
        // Return camera to non-tethered state so physical controls keep working
        // after disconnect. Ignore errors — we're tearing down anyway.
        sendAndLog(conn, OP_EOS_SET_EVENT_MODE, listOf(0), "SetEventMode(0)")
        sendAndLog(conn, OP_EOS_SET_REMOTE_MODE, listOf(0), "SetRemoteMode(0)")
    }

    private fun sendAndLog(conn: PtpConnection, op: Int, params: List<Int>, label: String): PtpResponse? {
        val txn = conn.sendCommand(op, params)
        if (txn < 0) { Log.w(TAG, "$label: sendCommand failed"); return null }
        val resp = conn.readResponse()
        if (resp == null) { Log.w(TAG, "$label: no response"); return null }
        if (!resp.isOk) Log.w(TAG, "$label: response=${resp.hex}")
        return resp
    }

    /** Canon EOS event list: UINT32 record_size | UINT32 event_type | params... repeated */
    private fun parseCanonEventList(data: ByteArray): List<Int> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val handles = mutableListOf<Int>()
        while (buf.remaining() >= 8) {
            val size = buf.int
            val type = buf.int
            if (size == 0 || type == 0) break
            val paramBytes = (size - 8).coerceAtLeast(0)
            val params = mutableListOf<Int>()
            var read = 0
            while (read + 4 <= paramBytes && buf.remaining() >= 4) {
                params.add(buf.int); read += 4
            }
            repeat((paramBytes - read).coerceAtLeast(0)) { if (buf.hasRemaining()) buf.get() }
            if (type == EOS_EV_OBJECT_ADDED) params.firstOrNull()?.let { handles.add(it) }
            if (type == EOS_EV_SHUTDOWN) Log.i(TAG, "Camera shutdown event received")
        }
        return handles
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Nikon adapter — stays with standard PTP + interrupt endpoint
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Nikon DSLRs generally honor standard PTP interrupt-endpoint events, so for transfer-only
 * workflows we behave like Generic. Nikon's own vendor opcodes (0x90xx / 0x9207 GetEvent)
 * are only needed for advanced capture control, not for receiving images the user shot
 * physically. If we ever need those, this is where they'd go.
 */
class NikonAdapter : VendorAdapter {
    override val name = "Nikon"
    private val delegate = GenericPtpAdapter()
    override fun pollNewImageHandles(core: PtpCore, conn: PtpConnection) =
        delegate.pollNewImageHandles(core, conn)
}
