// Copyright (c) 2026 Kriaa
// SPDX-License-Identifier: MIT

package `in`.kriaa.audiomicsync

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

object FileUploader {
    private const val MAX_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val RETRY_DELAY_MS = 1_500L

    data class UploadResult(
        val success: Boolean,
        val statusCode: Int? = null,
        val errorMessage: String? = null
    )

    fun upload(
        file: File,
        pcIp: String,
        port: Int,
        onProgress: (Int) -> Unit
    ): Boolean = uploadWithResult(file, pcIp, port, onProgress).success

    fun uploadWithResult(
        file: File,
        pcIp: String,
        port: Int,
        onProgress: (Int) -> Unit
    ): UploadResult {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val statusCode = uploadOnce(file, pcIp, port, onProgress)
                return if (statusCode in 200..299) {
                    UploadResult(success = true, statusCode = statusCode)
                } else {
                    UploadResult(
                        success = false,
                        statusCode = statusCode,
                        errorMessage = "server returned HTTP $statusCode"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }

        return UploadResult(
            success = false,
            errorMessage = describeConnectionError(pcIp, port, lastError)
        )
    }

    private fun uploadOnce(
        file: File,
        pcIp: String,
        port: Int,
        onProgress: (Int) -> Unit
    ): Int {
        val boundary = "----AudioMicSync${System.currentTimeMillis()}"
        val partHeader = (
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray()
        val partFooter = "\r\n--$boundary--\r\n".toByteArray()
        val totalLen = partHeader.size.toLong() + file.length() + partFooter.size.toLong()

        val conn = URL("http://$pcIp:$port/upload").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setFixedLengthStreamingMode(totalLen)

            conn.outputStream.buffered().use { out ->
                out.write(partHeader)

                val buf = ByteArray(8192)
                var sent = 0L
                file.inputStream().use { fis ->
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        onProgress((sent * 100 / file.length()).toInt())
                    }
                }
                out.write(partFooter)
            }

            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun describeConnectionError(pcIp: String, port: Int, error: Exception?): String {
        return when (error) {
            is SocketTimeoutException ->
                "could not reach $pcIp:$port after ${MAX_ATTEMPTS} attempts; check PC IP, receiver app, firewall, and Wi-Fi isolation"
            is ConnectException ->
                "connection refused by $pcIp:$port; start the PC receiver or check the upload port"
            is NoRouteToHostException ->
                "no route to $pcIp:$port; make sure phone and PC are on the same network"
            is UnknownHostException ->
                "unknown host $pcIp; check the PC IP address"
            is IOException ->
                error.message ?: "network error while connecting to $pcIp:$port"
            else ->
                error?.message ?: "unknown upload error while connecting to $pcIp:$port"
        }
    }
}
