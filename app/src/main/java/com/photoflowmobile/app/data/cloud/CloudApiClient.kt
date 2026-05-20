package com.photoflowmobile.app.data.cloud

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

    fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): Pair<Int, String> {
        val boundary = UUID.randomUUID().toString().replace("-", "")
        val conn = openConnection(path, "POST", readTimeoutMs = UPLOAD_TIMEOUT_MS)
        return try {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.outputStream.use { out ->
                fields.forEach { (name, value) ->
                    out.write("--$boundary$CRLF".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"$CRLF".toByteArray())
                    out.write(CRLF.toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write(CRLF.toByteArray())
                }
                out.write("--$boundary$CRLF".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$CRLF".toByteArray())
                out.write("Content-Type: $mimeType$CRLF".toByteArray())
                out.write(CRLF.toByteArray())
                out.write(fileBytes)
                out.write(CRLF.toByteArray())
                out.write("--$boundary--$CRLF".toByteArray())
            }
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String, method: String, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
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
