package com.sherva.voiceassistant.audio

import android.content.Context
import android.media.audiofx.AudioEffect
import com.sherva.voiceassistant.AppLog

/**
 * 设备 AEC（声学回声消除）探测。
 *
 * sherpa-onnx 不处理音频采集，AEC 是 Android 系统级 AudioEffect。
 * Android AEC 构造是 package-private，应用层不能直接 new。
 * 只能通过 [AudioEffect.queryEffects] 查询设备报告的能力。
 *
 * 返回的 Descriptor 含 type（AEC/NS/AGC）和连接标志。AEC
 * 由系统自动启用 VOICE_COMMUNICATION 时使用。
 */
object AecProbe {

    /** 探测结果。 */
    data class Result(
        val available: Boolean,
        val reason: String,
        val effects: List<String>,  // 调试用：列出所有支持的 AudioEffect
    )

    /**
     * 查询设备是否支持 AEC。
     * 不创建 AudioRecord、不实例化 AudioEffect，纯静态查询。
     */
    fun probeAec(context: Context): Result {
        // 1. 通过 AudioEffect.queryEffects 查询支持的 AudioEffect 描述符
        val descriptors: Array<AudioEffect.Descriptor> = runCatching {
            AudioEffect.queryEffects()
        }.getOrElse { e ->
            AppLog.e("AEC", "queryEffects 异常", e)
            return Result(false, "queryEffects 失败: ${e.message}", emptyList())
        }

        val names = descriptors.map { desc ->
            val typeName = when (desc.type) {
                AudioEffect.EFFECT_TYPE_AEC -> "AEC"
                AudioEffect.EFFECT_TYPE_NS -> "NS"
                AudioEffect.EFFECT_TYPE_AGC -> "AGC"
                else -> desc.type.toString().take(8)
            }
            "${desc.name}($typeName)"
        }
        AppLog.i("AEC", "设备支持的 AudioEffect: ${names.joinToString(", ")}")

        // AEC/NS/AGC 都是 UUID 类型，直接比较
        val hasAec = descriptors.any { it.type == AudioEffect.EFFECT_TYPE_AEC }
        val hasNs = descriptors.any { it.type == AudioEffect.EFFECT_TYPE_NS }
        val hasAgc = descriptors.any { it.type == AudioEffect.EFFECT_TYPE_AGC }

        val reasons = buildList {
            if (hasAec) add("AEC") else add("AEC不支持")
            if (hasNs) add("NS") else add("NS不支持")
            if (hasAgc) add("AGC") else add("AGC不支持")
        }
        val reason = reasons.joinToString(", ")
        AppLog.i("AEC", "探测结果: AEC=$hasAec NS=$hasNs AGC=$hasAgc")
        return Result(hasAec, reason, names)
    }

    }