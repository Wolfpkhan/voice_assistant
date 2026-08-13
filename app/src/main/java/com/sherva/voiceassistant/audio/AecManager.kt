package com.sherva.voiceassistant.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import com.sherva.voiceassistant.AppLog

/**
 * 系统硬件回声消除（AEC）封装。
 *
 * 方案 B（推荐）：用 [AcousticEchoCanceler] 附加 API 启用硬件/固件 AEC，
 * 保持 [android.media.AudioManager.MODE_NORMAL]，不切听筒模式、不抢音频焦点。
 *
 * 历史方案 A（已废弃）：`AudioSource.VOICE_COMMUNICATION` +
 * `AudioManager.setMode(MODE_IN_COMMUNICATION)`，代价是扬声器切到听筒。
 *
 * vivo V2303A 实测（2026-08）：
 *   - `AcousticEchoCanceler.create(sessionId)` 返回非 null
 *   - TTS 播报期间 rms=0.0001（vs 无 AEC 0.05~0.3）
 *   - TTS 不再误唤醒 KWS
 */
object AecManager {
    private const val TAG = "AEC"

    /** 在 AudioRecord 上启用硬件 AEC。返回 AEC 句柄，调用方持有并负责 release。 */
    fun enable(record: AudioRecord): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) {
            AppLog.w(TAG, "设备不支持声AcousticEchoCanceler（AEC 不可用）")
            return null
        }
        val sessionId = record.audioSessionId
        val aec = AcousticEchoCanceler.create(sessionId)
        if (aec == null) {
            AppLog.w(TAG, "AcousticEchoCanceler.create(sessionId=$sessionId) 返回 null")
            return null
        }
        aec.enabled = true
        AppLog.i(TAG, "✓ 硬件 AEC 已启用 (sessionId=$sessionId)")
        return aec
    }

    fun disable(aec: AcousticEchoCanceler?) {
        if (aec == null) return
        runCatching {
            aec.enabled = false
            aec.release()
        }
    }
}