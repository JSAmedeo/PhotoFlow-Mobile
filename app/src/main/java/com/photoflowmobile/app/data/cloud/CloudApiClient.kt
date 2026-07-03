package com.photoflowmobile.app.data.cloud

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class CloudApiClient(val baseUrl: String, private val apiKey: String = "") {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 15_000
        private const val UPLOAD_TIMEOUT_MS  = 60_000
        private const val CRLF = "\r\n"
        private const val DASH2 = "--"

        /** Maps a filename extension to a MIME type for multipart uploads. */
        fun mimeTypeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png"         -> "image/png"
                "heic", "heif" -> "image/heic"
                "cr2", "cr3", "nef", "arw" -> "application/octet-stream"
                else          -> "application/octet-stream"
            }
    }

    fun get(path: String): Pair<Int, String> {
        val conn = openConnection(path, "GET")
        return try {
            conn.connect()
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun getBytes(path: String): Pair<Int, ByteArray> {
        val conn = openConnection(path, "GET", readTimeoutMs = UPLOAD_TIMEOUT_MS)
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            Pair(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(path: String, jsonBody: String): Pair<Int, String> {
        val conn = openConnection(path, "POST")
        return try {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams a multipart/form-data POST using [imageFile] directly, avoiding a full
     * in-memory copy of the file. Uses fixed-length streaming mode so HttpURLConnection
     * does not buffer the entire request body before sending.
     *
     * MIME type is derived from [imageFile]'s extension via [mimeTypeFor] if not provided.
     */
    fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        imageFile: File,
        mimeType: String = mimeTypeFor(fileName)
    ): Pair<Int, String> {
        val boundary = UUID.randomUUID().toString().replace("-", "")

        fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

        // Pre-compute exact Content-Length so fixed-length streaming mode can be used.
        // This prevents HttpURLConnection from buffering the entire body in memory.
        var totalLength = 0L
        fields.forEach { (name, value) ->
            totalLength += bytes("$DASH2$boundary$CRLF").size
            totalLength += bytes("Content-Disposition: form-data; name=\"$name\"$CRLF").size
            totalLength += bytes(CRLF).size
            totalLength += value.toByteArray(Charsets.UTF_8).size
            totalLength += bytes(CRLF).size
        }
        totalLength += bytes("$DASH2$boundary$CRLF").size
        totalLength += bytes("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$CRLF").size
        totalLength += bytes("Content-Type: $mimeType$CRLF").size
        totalLength += bytes(CRLF).size
        totalLength += imageFile.length()
        totalLength += bytes(CRLF).size
        totalLength += bytes("$DASH2$boundary$DASH2$CRLF").size

        val conn = openConnection(path, "POST", readTimeoutMs = UPLOAD_TIMEOUT_MS)
        return try {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(totalLength)
            conn.outputStream.use { out ->
                fields.forEach { (name, value) ->
                    out.write(bytes("$DASH2$boundary$CRLF"))
                    out.write(bytes("Content-Disposition: form-data; name=\"$name\"$CRLF"))
                    out.write(bytes(CRLF))
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write(bytes(CRLF))
                }
                out.write(bytes("$DASH2$boundary$CRLF"))
                out.write(bytes("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$CRLF"))
                out.write(bytes("Content-Type: $mimeType$CRLF"))
                out.write(bytes(CRLF))
                imageFile.inputStream().use { it.copyTo(out, bufferSize = 16 * 1024) }
                out.write(bytes(CRLF))
                out.write(bytes("$DASH2$boundary$DASH2$CRLF"))
            }
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String, method: String, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        // Refuse plaintext HTTP to non-LAN hosts: API key travels in the header and must not
        // cross the public internet unencrypted. LAN/loopback IPs are permitted for staging.
        if (url.protocol == "http" && !PrivateAddressChecker.isPrivate(url.host)) {
            throw IllegalArgumentException(
                "Cloud API over plain HTTP is only allowed for LAN addresses — use https:// for internet hosts"
            )
        }
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        if (apiKey.isNotBlank()) conn.setRequestProperty("X-PhotoFlow-Api-Key", apiKey)
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream: InputStream? = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return Pair(code, body)
    }
}
