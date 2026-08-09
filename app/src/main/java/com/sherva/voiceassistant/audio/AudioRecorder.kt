package com.sherva.voiceassistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlin.math.min

/**
 * 16kHz 单声道 PCM 录音器：持续采集并以 float[]（-1~1）回调。
 *
 * 采集规格与 VAD/ASR 对齐：
 *   sampleRate = 16000, 单声道, 16bit
 *
 * 每次回调一个 [chunkSamples] 大小的帧（默认 512，即 silero-vad 的窗口）。
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val chunkSamples: Int = 512,   // silero-vad 16kHz 窗口
) {
    companion object { private const val TAG = "AudioRecorder" }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var running = false

    /** 录音权限检查（调用方负责已获得 RECORD_AUDIO）。 */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope, onChunk: (FloatArray) -> Unit) {
        if (running) return
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(chunkSamples * 2 * 4)

        @Suppress("MissingPermission")
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 噪声抑制友好
            sampleRate, channelConfig, audioFormat, bufBytes
        )
        check(record?.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord 初始化失败"
        }
        record!!.startRecording()
        running = true

        job = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(chunkSamples)
            while (isActive && running) {
                val n = record!!.read(shortBuf, 0, chunkSamples)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.w(TAG, "read 返回 $n，跳过")
                    }
                    continue
                }
                val len = min(n, chunkSamples)
                val floats = FloatArray(len) { shortBuf[it] / 32768.0f }
                onChunk(floats)
            }
        }
        Log.i(TAG, "录音已启动 ${sampleRate}Hz, chunk=$chunkSamples")
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        try {
            record?.stop()
        } catch (_: Throwable) {}
        record?.release()
        record = null
        Log.i(TAG, "录音已停止")
    }
}
