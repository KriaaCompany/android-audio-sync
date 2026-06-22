// Copyright (c) 2026 Kriaa
// SPDX-License-Identifier: MIT

package `in`.kriaa.audiomicsync

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object WavWriter {
    fun finalize(pcmFile: File, wavFile: File, sampleRate: Int) {
        val dataSize = pcmFile.length().toInt()
        FileOutputStream(wavFile).use { out ->
            writeHeader(out, sampleRate, dataSize)
            pcmFile.inputStream().use { it.copyTo(out) }
        }
        pcmFile.delete()
    }

    private fun writeHeader(out: OutputStream, sampleRate: Int, dataSize: Int) {
        val byteRate = sampleRate * 2   // mono, 16-bit → 2 bytes/sample
        out.write("RIFF".toByteArray())
        out.intLE(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.intLE(16)         // chunk size
        out.shortLE(1)        // PCM
        out.shortLE(1)        // mono
        out.intLE(sampleRate)
        out.intLE(byteRate)
        out.shortLE(2)        // block align
        out.shortLE(16)       // bits per sample
        out.write("data".toByteArray())
        out.intLE(dataSize)
    }

    private fun OutputStream.intLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun OutputStream.shortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
