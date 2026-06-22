package com.example.audiomicsync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object FileUploader {
    fun upload(
        file: File,
        pcIp: String,
        port: Int,
        onProgress: (Int) -> Unit
    ): Boolean {
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
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
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

            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}
