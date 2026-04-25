package com.photoflowmobile.app.data.usb

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vendor-neutral PTP operations defined in CIPA DC-X005.
 * Every tethering-capable DSLR (Canon/Nikon/Sony/Fuji/Panasonic/Olympus/Pentax) supports these.
 *
 * Vendor-specific behavior (Canon's 0x91xx, Nikon's extensions, etc.) lives in VendorAdapter
 * implementations — not here.
 */
class PtpCore(private val conn: PtpConnection) {

    companion object {
        private const val TAG = "PhotoFlow/PtpCore"

        // Standard PTP operations (CIPA DC-X005 / PIMA 15740)
        const val OP_GET_DEVICE_INFO     = 0x1001
        const val OP_OPEN_SESSION        = 0x1002
        const val OP_CLOSE_SESSION       = 0x1003
        const val OP_GET_STORAGE_IDS     = 0x1004
        const val OP_GET_NUM_OBJECTS     = 0x1006
        const val OP_GET_OBJECT_HANDLES  = 0x1007
        const val OP_GET_OBJECT_INFO     = 0x1008
        const val OP_GET_OBJECT          = 0x1009
        const val OP_GET_THUMB           = 0x100A

        // Standard PTP event codes (delivered on interrupt endpoint)
        const val EV_OBJECT_ADDED        = 0x4002
        const val EV_OBJECT_REMOVED      = 0x4003
        const val EV_STORE_ADDED         = 0x4004
        const val EV_CAPTURE_COMPLETE    = 0x400D

        // Object formats (PTP standard + common Canon/Nikon RAW)
        const val FORMAT_JPEG = 0x3801
        const val FORMAT_TIFF = 0x380D
        const val FORMAT_CRW  = 0xB101  // Canon RAW 1
        const val FORMAT_CR2  = 0xB103  // Canon RAW 2
        const val FORMAT_CR3  = 0xB104  // Canon RAW 3
        const val FORMAT_NEF  = 0xB102  // Nikon RAW (same code as CRW? actually NEF uses 0x3800/vendor)

        // Format ID passed to GetObjectHandles to mean "all formats"
        private const val ANY_FORMAT = 0x00000000
        // Object handle root for "all objects in storage"
        private const val ANY_PARENT = 0x00000000
    }

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * @return a PtpResponse describing success/failure. Caller should treat
     *         RESPONSE_OK and RESPONSE_SESSION_ALREADY_OPEN as success.
     */
    fun openSession(sessionId: Int = 1): PtpResponse? {
        val txn = conn.sendCommand(OP_OPEN_SESSION, listOf(sessionId))
        if (txn < 0) {
            Log.w(TAG, "openSession: sendCommand failed")
            return null
        }
        val resp = conn.readResponse()
        if (resp == null) {
            Log.w(TAG, "openSession: no response")
            return null
        }
        Log.d(TAG, "openSession response=${resp.hex}")
        return resp
    }

    fun closeSession(): Boolean {
        val txn = conn.sendCommand(OP_CLOSE_SESSION)
        if (txn < 0) return false
        return conn.readResponse()?.isOk == true
    }

    // ── Storage / object enumeration ─────────────────────────────────────────

    fun getStorageIds(): IntArray {
        val txn = conn.sendCommand(OP_GET_STORAGE_IDS)
        if (txn < 0) return IntArray(0)
        val (data, resp) = conn.readDataAndResponse() ?: return IntArray(0)
        if (!resp.isOk) return IntArray(0)
        return parseUint32Array(data)
    }

    /**
     * List all object handles currently on the given storage, filtered to image formats
     * (JPEG + Canon/Nikon RAW). Passing storageId=0xFFFFFFFF queries all storages.
     */
    fun getImageObjectHandles(storageId: Int = -1): IntArray {
        val txn = conn.sendCommand(
            OP_GET_OBJECT_HANDLES,
            listOf(storageId, ANY_FORMAT, ANY_PARENT)
        )
        if (txn < 0) return IntArray(0)
        val (data, resp) = conn.readDataAndResponse() ?: return IntArray(0)
        if (!resp.isOk) return IntArray(0)
        return parseUint32Array(data)
    }

    fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val txn = conn.sendCommand(OP_GET_OBJECT_INFO, listOf(handle))
        if (txn < 0) return null
        val (data, resp) = conn.readDataAndResponse() ?: return null
        if (!resp.isOk || data.size < 52) return null
        return parseObjectInfo(data)
    }

    fun getObject(handle: Int): ByteArray? {
        val txn = conn.sendCommand(OP_GET_OBJECT, listOf(handle))
        if (txn < 0) return null
        val (data, resp) = conn.readDataAndResponse(largeData = true) ?: return null
        if (!resp.isOk || data.isEmpty()) return null
        return data
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    private fun parseUint32Array(data: ByteArray): IntArray {
        if (data.size < 4) return IntArray(0)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        if (count <= 0 || count > 1_000_000) return IntArray(0)
        val out = IntArray(count.coerceAtMost((data.size - 4) / 4))
        for (i in out.indices) out[i] = buf.int
        return out
    }

    private fun parseObjectInfo(data: ByteArray): PtpObjectInfo? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.int                                    // StorageID
        val format = buf.short.toInt() and 0xFFFF
        buf.short                                  // ProtectionStatus
        val fileSize = buf.int
        buf.short                                  // ThumbFormat
        buf.int                                    // ThumbCompressedSize
        buf.int; buf.int                           // ThumbPixWidth/Height
        buf.int; buf.int                           // ImagePixWidth/Height
        buf.int                                    // ImageBitDepth
        buf.int                                    // ParentObject
        buf.short                                  // AssociationType
        buf.int                                    // AssociationDesc
        buf.int                                    // SequenceNumber
        val filename = readPtpString(buf) ?: "image.jpg"
        return PtpObjectInfo(format, fileSize, filename)
    }

    private fun readPtpString(buf: ByteBuffer): String? {
        if (!buf.hasRemaining()) return null
        val len = buf.get().toInt() and 0xFF
        if (len == 0) return ""
        val sb = StringBuilder(len)
        repeat(len) {
            if (buf.remaining() >= 2) sb.append(buf.short.toInt().toChar())
        }
        return sb.toString().trimEnd('\u0000')
    }
}

data class PtpObjectInfo(val format: Int, val fileSize: Int, val filename: String) {
    val isImage: Boolean
        get() = format == PtpCore.FORMAT_JPEG ||
                format == PtpCore.FORMAT_TIFF ||
                format == PtpCore.FORMAT_CRW  ||
                format == PtpCore.FORMAT_CR2  ||
                format == PtpCore.FORMAT_CR3  ||
                // Catch-all: Canon/Nikon/Sony vendor raw codes live in 0xB1xx / 0x3800 ranges
                (format and 0xF000) == 0xB000 ||
                format == 0x3800
}
