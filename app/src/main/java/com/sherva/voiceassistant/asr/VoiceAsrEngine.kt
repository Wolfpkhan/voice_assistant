package com.sherva.voiceassistant.asr

/**
 * 语音识别引擎公共接口（对齐 StreamingAsrEngine 的使用方式）。
 *
 * VoiceAssistant 通过该接口无差别使用流式(CPU)与 QNN(NPU 模拟流式)两种实现：
 *   - StreamingAsrEngine : OnlineRecognizer 边说边出字 + 内置端点检测
 *   - QnnAsrEngine       : silero VAD 端点 + 离线 QNN paraformer 滑窗出字
 */
interface VoiceAsrEngine {
    /**
     * 启动识别（含录音）。
     * @param onPartial 实时文本（会连续纠正更新），UI 直接覆盖显示
     * @param onFinal   一句话说完的最终文本
     */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit)

    /** 停止识别与录音。 */
    fun stop()

    /** 撤销当前累积的 partial 文本并重新聆听（不重启引擎）。 */
    fun resetStream()

    /** 释放底层资源（模型/识别器）。 */
    fun release()
}
