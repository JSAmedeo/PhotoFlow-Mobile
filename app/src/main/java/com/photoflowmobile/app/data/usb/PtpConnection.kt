package com.photoflowmobile.app.data.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.photoflowmobile.app.data.logging.PhotoFlowLog as Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB bulk-transfer layer for PTP/USB (CIPA DC-X005).
 *
 * Vendor-neutral. Handles the three PTP container phases (command, data, response)
 * plus the standard interrupt-endpoint event channel used by most DSLR vendors.
 */
class PtpConnection(
    private val conn: UsbDeviceConnection,
    private val usbInterface: UsbInterface
) {
    companion object {
        private const val TAG = "PhotoFlow/PTP"

        // PTP standard response codes
        const val RESPONSE_OK                   = 0x2001
        const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E

        // PTP container types
        private const val TYPE_COMMAND  = 0x0001
        private const val TYPE_DATA     = 0x0002
        private const val TYPE_RESPONSE = 0x0003
        private const val TYPE_EVENT    = 0x0004

        private const val HEADER_BYTES       = 12      // length(4) + type(2) + code(2) + txnId(4)
        private const val TIMEOUT_MS         = 5_000
        private const val TIMEOUT_LARGE_MS   = 30_000
        private const val TIMEOUT_INT_MS     = 50       // interrupt endpoint polls must be short
        // First read of a data-phase response: keep small. Samsung's USB host driver returns
        // -1 immediately when bulkTransfer is asked for a full 64 KB on the first read after
        // vendor-extension commands. 512 is the USB 2.0 high-speed bulk max packet size —
        // enough to hold the 12-byte data header + most small data payloads, and safe for
        // larger payloads too (we continue reading in CHUNK_SIZE chunks below).
        private const val FIRST_CHUNK_SIZE   = 512
        private const val CHUNK_SIZE         = 16_384

        /**
         * Payload size implied by a PTP container-length header.
         *
         * Cameras streaming data of unknown length send 0xFFFFFFFF, which reads back as -1.
         * That — and any other header too short to contain a payload — yields 0, which callers
         * must read as "length not declared, nothing to enforce" rather than "expect zero bytes".
         * The truncation check in [readDataAndResponse] relies on this: a received count is never
         * negative, so it can never be below a 0 expectation, and unknown-length transfers are
         * left alone.
         */
        internal fun expectedPayloadBytes(containerLen: Int): Int =
            (containerLen - HEADER_BYTES).coerceAtLeast(0)
    }

    private var txnId = 1
    private val bulkIn: UsbEndpoint
    private val bulkOut: UsbEndpoint
    private val interruptIn: UsbEndpoint?

    init {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        var intrEp: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                UsbConstants.USB_ENDPOINT_XFER_INT -> {
                    if (ep.direction == UsbConstants.USB_DIR_IN) intrEp = ep
                }
            }
        }
        bulkIn      = requireNotNull(inEp)  { "No bulk IN endpoint on PTP interface" }
        bulkOut     = requireNotNull(outEp) { "No bulk OUT endpoint on PTP interface" }
        interruptIn = intrEp

        val claimed = conn.claimInterface(usbInterface, true)
        if (!claimed) throw IllegalStateException("claimInterface returned false — interface busy")

        // SET_INTERFACE(0) resets the alternate setting and clears endpoint toggle bits —
        // stronger than CLEAR_HALT alone. Some DSLRs need this to recover from stale state
        // left by the system MTP daemon.
        val setIfaceResult = conn.controlTransfer(0x01, 0x0B, 0, usbInterface.id, null, 0, 1_000)
        Log.d(TAG, "SET_INTERFACE result=$setIfaceResult")

        clearHalt(bulkOut)
        clearHalt(bulkIn)

        // Samsung's system MTP daemon needs time to fully back off after we force-claimed
        // the interface. 800 ms is empirically enough on One UI.
        Thread.sleep(800)
        Log.d(TAG, "PtpConnection ready — in=0x${bulkIn.address.toString(16)} " +
                "out=0x${bulkOut.address.toString(16)} " +
                "intr=${interruptIn?.address?.toString(16)?.let { "0x$it" } ?: "none"}")
    }

    fun release() {
        try { conn.releaseInterface(usbInterface) } catch (_: Exception) {}
    }

    private fun clearHalt(ep: UsbEndpoint) {
        val result = conn.controlTransfer(0x02, 0x01, 0x0000, ep.address, null, 0, 1_000)
        Log.d(TAG, "clearHalt ep=0x${ep.address.toString(16)} result=$result")
    }

    // ── Command / Response ────────────────────────────────────────────────────

    fun sendCommand(opCode: Int, params: List<Int> = emptyList()): Int {
        val id = txnId++
        val len = HEADER_BYTES + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(len)
            putShort(TYPE_COMMAND.toShort())
            putShort((opCode and 0xFFFF).toShort())
            putInt(id)
            params.forEach { putInt(it) }
        }
        val sent = conn.bulkTransfer(bulkOut, buf.array(), len, TIMEOUT_MS)
        if (sent != len) {
            Log.w(TAG, "sendCommand 0x${opCode.toString(16)} txn=$id: sent=$sent expected=$len")
        }
        return if (sent == len) id else -1
    }

    fun readResponse(): PtpResponse? {
        val buf = ByteArray(64)
        val read = conn.bulkTransfer(bulkIn, buf, buf.size, TIMEOUT_MS)
        if (read < HEADER_BYTES) {
            Log.w(TAG, "readResponse: short read=$read")
            return null
        }
        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        bb.int                                       // length
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int                                       // txnId
        if (type != TYPE_RESPONSE) {
            Log.w(TAG, "readResponse: expected type=$TYPE_RESPONSE got type=$type code=0x${code.toString(16)}")
            return null
        }
        val params = mutableListOf<Int>()
        while (bb.remaining() >= 4) params.add(bb.int)
        return PtpResponse(code, params)
    }

    fun readDataAndResponse(largeData: Boolean = false): Pair<ByteArray, PtpResponse>? {
        val timeout = if (largeData) TIMEOUT_LARGE_MS else TIMEOUT_MS

        val firstBuf = ByteArray(FIRST_CHUNK_SIZE)
        val firstRead = conn.bulkTransfer(bulkIn, firstBuf, firstBuf.size, timeout)
        if (firstRead < HEADER_BYTES) {
            Log.w(TAG, "readDataAndResponse: first read=$firstRead")
            return null
        }

        val hdr = ByteBuffer.wrap(firstBuf, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
        val containerLen = hdr.int
        val type = hdr.short.toInt() and 0xFFFF
        val code = hdr.short.toInt() and 0xFFFF
        hdr.int    // txnId

        // Some cameras (notably Canon EOS with an empty GetEvent queue) skip the data phase
        // entirely and return only a response container. That's legal PTP — treat it as
        // "empty data, here's your response" rather than a protocol error.
        if (type == TYPE_RESPONSE) {
            val params = mutableListOf<Int>()
            while (hdr.remaining() >= 4) params.add(hdr.int)
            return ByteArray(0) to PtpResponse(code, params)
        }

        if (type != TYPE_DATA) {
            Log.w(TAG, "readDataAndResponse: expected type=$TYPE_DATA got type=$type")
            return null
        }

        val expectedPayload = expectedPayloadBytes(containerLen)
        val payloadInFirst  = (firstRead - HEADER_BYTES).coerceAtLeast(0)

        val chunks = ArrayList<ByteArray>(4)
        if (payloadInFirst > 0) chunks.add(firstBuf.copyOfRange(HEADER_BYTES, firstRead))
        var received = payloadInFirst

        while (received < expectedPayload) {
            val chunk = ByteArray(CHUNK_SIZE)
            val n = conn.bulkTransfer(bulkIn, chunk, chunk.size, timeout)
            if (n <= 0) break
            chunks.add(chunk.copyOf(n))
            received += n
        }

        // A truncated data phase must never surface as a valid payload. Without this check a
        // mid-transfer USB hiccup yields a partial JPEG that gets saved, renamed, shown in the
        // review pane and uploaded, with nothing anywhere indicating it is incomplete.
        //
        // Cameras that stream with an unknown container length send 0xFFFFFFFF, which reads back
        // as -1 and coerces expectedPayload to 0 — that path is unaffected, since `received` is
        // never negative.
        if (received < expectedPayload) {
            Log.e(TAG, "readDataAndResponse: TRUNCATED — expected $expectedPayload B, got $received B " +
                    "(container=$containerLen)")
            // Best-effort drain so a response container left queued by the aborted transfer does
            // not get parsed as the *next* command's response. Bounded by its own timeout; the
            // result is deliberately discarded because the transfer has already failed.
            runCatching { readResponse() }
            return null
        }

        val data = ByteArray(received)
        var off = 0
        for (chunk in chunks) { chunk.copyInto(data, off); off += chunk.size }

        val response = readResponse() ?: return null
        return data to response
    }

    // ── Standard PTP event channel (interrupt endpoint) ───────────────────────

    /**
     * True if the device exposes the standard PTP interrupt-endpoint event channel.
     * Most DSLRs do — Canon EOS is an exception (it uses polled GetEvent instead).
     */
    val hasInterruptEvents: Boolean get() = interruptIn != null

    /**
     * Non-blocking read of one pending interrupt-endpoint event.
     * Returns null if no event is pending or the endpoint is absent.
     *
     * Standard PTP event container: length(4) | type=0x0004 | eventCode(2) | txnId(4) | params...
     */
    fun readInterruptEvent(): PtpEvent? {
        val ep = interruptIn ?: return null
        val buf = ByteArray(64)
        val read = conn.bulkTransfer(ep, buf, buf.size, TIMEOUT_INT_MS)
        if (read < HEADER_BYTES) return null
        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        bb.int                                       // length
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int                                       // txnId
        if (type != TYPE_EVENT) return null
        val params = mutableListOf<Int>()
        while (bb.remaining() >= 4) params.add(bb.int)
        return PtpEvent(code, params)
    }
}

data class PtpResponse(val code: Int, val params: List<Int> = emptyList()) {
    val isOk: Boolean get() = code == PtpConnection.RESPONSE_OK
    val hex: String get() = "0x${code.toString(16).padStart(4, '0')}"
}

data class PtpEvent(val code: Int, val params: List<Int> = emptyList())
