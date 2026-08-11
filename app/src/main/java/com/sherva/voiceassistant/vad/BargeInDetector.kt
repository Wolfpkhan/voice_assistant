package com.sherva.voiceassistant.vad

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.SpeechEnhancer
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 打断检测器（Barge-in）：TTS 播报期间监听麦克风，一旦检测到用户开口说话立即回调。
 *
 * ★ 与 VadEngine 的区别：这里只关心"有没有人声出现"，不收集完整语音段，延迟极低。
 *
 * 防回声策略（避免把 TTS 自己播出的声音误判为打断）：
 *   1. 高阈值 threshold：远端扬声器声音能量通常低于近端人声，提高门槛
 *   2. 起播保护期 startGuardMs：TTS 刚开始播的头几百毫秒不检测（避开启动瞬态/响度爬升）
 *   3. 持续确认 minSpeechMs：连续检测到语音达到该时长才确认是真打断（一次抖动不算）
 *
 * 使用：
 *   detector.start { onInterrupt() }   // TTS 开始播时调用
 *   detector.stop()                     // TTS 播完/被中断时调用
 */
class BargeInDetector(
    context: Context,
    private val threshold: Float,        // VAD 阈值（越小越灵敏）
    private val startGuardMs: Long,      // 起播保护期
    private val minSpeechMs: Long,       // 连续语音多久才算打断
) {
    private val denoiser = SpeechEnhancer(context)
    private val vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelPaths.VAD_MODEL,
                threshold = threshold,
                // ★ minSilenceDuration = 0.5：单点噪声不算静音结束
                minSilenceDuration = 0.5f,
                // ★ minSpeechDuration = 0.3：需要 300ms 连续人声才认是语音
                //   0.1f 太短，TTS 漏音中偶尔超过阈值就被计入
                minSpeechDuration = 0.3f,
                windowSize = 512,
                maxSpeechDuration = 30f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
    )
    private val appContext = context.applicationContext

    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var armed = false   // 是否已过保护期、开始真正检测
    private var workThread: Thread? = null

    /**
     * 开始监听。检测到用户开口时回调 [onInterrupt]（仅触发一次，随后自动停止）。
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onInterrupt: () -> Unit) {
        if (running) return
        val sampleRate = 16000
        val bufBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(512 * 2 * 4)
        @Suppress("MissingPermission")
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // ★ 开启系统 AEC + NS 回声消除
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        record!!.startRecording()
        // ★★★ 关键：切换 AudioManager 到 IN_COMMUNICATION 模式
        //   否则仅 AudioSource=VOICE_COMMUNICATION 系统不会启用 AEC
        //   （vivo/多数厂商要求显式 setMode 才能拿到 TTS 参考信号）
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        AppLog.i("BargeIn", "AudioManager.mode = MODE_IN_COMMUNICATION（AEC 真正启用）")
        running = true
        armed = false
        vad.reset()
        AppLog.i("BargeIn", "打断监听已启动（保护期 ${startGuardMs}ms）")

        workThread = thread(true, name = "barge-in") {
            // 起播保护期
            val guardEnd = System.currentTimeMillis() + startGuardMs
            val buf = ShortArray(512)
            var speechStart = 0L
            // ★ GTCRN 输出能量基线校准：仅参考非零输出帧
            var baselineRms = 0.005f
            // ★ 连续帧确认：silero 检测到人声后还要连续 N 帧才算
            var voiceConfirmCount = 0
            val requiredConfirmFrames = 3  // 3帧 * 32ms = 96ms
            var logCounter = 0  // 诊断日志计数
            try {
                while (running) {
                    if (!armed && System.currentTimeMillis() >= guardEnd) {
                        armed = true
                        speechStart = 0L
                        AppLog.i("BargeIn", "保护期结束，开始检测打断")
                    }
                    val n = record!!.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val raw = FloatArray(n) { buf[it] / 32768.0f }
                    // ★ GTCRN 实时消回声：剩嘴人声 → 再喂 VAD
                    val clean = denoiser.process(raw, 16000)
                    if (clean.isEmpty()) continue
                    // ★ 计算 GTCRN 输出能量
                    var sumSq = 0f
                    for (s in clean) sumSq += s * s
                    val rms = sqrt(sumSq / clean.size)
                    // 更新能量基线（EWMA，仅考虑非零样本）
                    if (rms > 0.0005f) {
                        baselineRms = 0.95f * baselineRms + 0.05f * rms
                    }
                    // ★ 诊断日志：每 30 帧（约1秒）打一次 RMS/基线/VAD 状态
                    logCounter++
                    if (logCounter % 30 == 0) {
                        AppLog.i("BargeIn", "诊断: rms=${String.format("%.4f", rms)} baseline=${String.format("%.4f", baselineRms)} ratio=${String.format("%.1f", rms / baselineRms)} armed=$armed")
                    }
                    // ★ 能量门：RMS 低于基线 2.0 倍认为是 TTS 漏音残余，不调 VAD
                    //   （从 2.5 降到 2.0，避免真人声音被过滤）
                    if (rms < baselineRms * 2.0f) continue
                    vad.acceptWaveform(clean)
                    // 排空（不关心段，只看 isSpeechDetected）
                    while (!vad.empty()) vad.pop()

                    if (!armed) continue
                    val speaking = vad.isSpeechDetected()
                    if (speaking) {
                        voiceConfirmCount++
                        AppLog.i("BargeIn", "VAD人声 frame#$voiceConfirmCount rms=${String.format("%.4f", rms)}")
                        if (voiceConfirmCount >= requiredConfirmFrames) {
                            AppLog.i("BargeIn", "★ 检测到用户打断！（连续 ${voiceConfirmCount} 帧人声）")
                            running = false
                            onInterrupt()
                            return@thread
                        }
                    } else {
                        voiceConfirmCount = 0
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("BargeIn", "打断监听异常", e)
            } finally {
                AppLog.i("BargeIn", "打断监听线程结束")
            }
        }
    }

    fun stop() {
        running = false
        armed = false
        workThread?.join(200)
        workThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        // ★ 退出 IN_COMMUNICATION 模式，恢复正常媒体音量路由
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    fun release() {
        stop()
        vad.release()
        denoiser.release()
    }
}
