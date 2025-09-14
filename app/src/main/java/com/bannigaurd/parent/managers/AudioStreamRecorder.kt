package com.bannigaurd.parent.managers

import android.media.audiofx.Visualizer
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

// ✅ FIX: पूरी क्लास को Visualizer का उपयोग करने के लिए फिर से लिखा गया है
class AudioStreamRecorder {

    private var visualizer: Visualizer? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var isRecording = false
    private var audioDataSize = 0L

    private var sampleRate = 48000 // डिफ़ॉल्ट, विज़ुअलाइज़र से अपडेट होगा
    private val numChannels = 1 // मोनो
    private val bitsPerSample = 16 // 16-bit PCM

    private val TAG = "AudioStreamRecorder"

    fun start(outputFile: File) {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress.")
            return
        }

        try {
            // ऑडियो सेशन 0 (ग्लोबल मिक्स) पर विज़ुअलाइज़र शुरू करें
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                sampleRate = this.samplingRate
                setDataCaptureListener(dataCaptureListener, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }

            randomAccessFile = RandomAccessFile(outputFile, "rw")
            writeWavHeaderPlaceholder()
            isRecording = true
            audioDataSize = 0
            Log.d(TAG, "Recording started, saving to: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stop() // कुछ गलत होने पर सब कुछ साफ़ करें
        }
    }

    fun stop() {
        if (!isRecording && visualizer == null) return

        isRecording = false
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null

        try {
            randomAccessFile?.let {
                updateWavHeader(it)
                it.close()
            }
            randomAccessFile = null
            Log.d(TAG, "Recording stopped. Final size: $audioDataSize bytes")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to stop recording cleanly", e)
        }
    }

    private val dataCaptureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray, samplingRate: Int) {
            if (!isRecording || randomAccessFile == null) return
            try {
                randomAccessFile?.write(waveform)
                audioDataSize += waveform.size
            } catch (e: IOException) {
                Log.e(TAG, "Error writing audio data to file", e)
            }
        }
        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
    }

    private fun writeWavHeaderPlaceholder() {
        randomAccessFile?.write(ByteArray(44))
    }

    private fun updateWavHeader(file: RandomAccessFile) {
        file.seek(0) // फाइल की शुरुआत में जाएं

        val byteRate = (sampleRate * numChannels * bitsPerSample) / 8
        val blockAlign = (numChannels * bitsPerSample) / 8

        val header = ByteBuffer.allocate(44)
        header.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put('R'.code.toByte())
        header.put('I'.code.toByte())
        header.put('F'.code.toByte())
        header.put('F'.code.toByte())
        header.putInt((36 + audioDataSize).toInt())
        header.put('W'.code.toByte())
        header.put('A'.code.toByte())
        header.put('V'.code.toByte())
        header.put('E'.code.toByte())

        // "fmt" sub-chunk
        header.put('f'.code.toByte())
        header.put('m'.code.toByte())
        header.put('t'.code.toByte())
        header.put(' '.code.toByte())
        header.putInt(16) // Sub-chunk size
        header.putShort(1) // Audio format (1 for PCM)
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        header.put('d'.code.toByte())
        header.put('a'.code.toByte())
        header.put('t'.code.toByte())
        header.put('a'.code.toByte())
        header.putInt(audioDataSize.toInt())

        file.write(header.array())
    }
}