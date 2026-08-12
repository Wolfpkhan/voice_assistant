package com.sherva.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.SeekBarPreference
import com.sherva.voiceassistant.service.VoiceAssistantService
import com.sherva.voiceassistant.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页：配置云端 LLM 与 TTS 参数。 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var ttsEngine: TtsEngine? = null
        private var audioTrack: AudioTrack? = null
        private var previewing = false

        /** 通知权限 launcher (Android 13+)，用户授权后启动服务 */
        private val notifPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Toast.makeText(requireContext(), "通知权限已授予", Toast.LENGTH_SHORT).show()
                proceedStart()
            } else {
                Toast.makeText(requireContext(), "未授通知权限，服务可能被系统杀", Toast.LENGTH_LONG).show()
                proceedStart()  // 即使没权限也启动
            }
        }

        /** 悬浮窗权限 launcher，用户在设置页授权后返回这里 */
        private val overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
                proceedStart()
            } else {
                Toast.makeText(requireContext(), "未授悬浮窗权限，只能后台保活", Toast.LENGTH_LONG).show()
                proceedStart()
            }
        }

        private fun proceedStart() {
            val intent = Intent(requireContext(), VoiceAssistantService::class.java)
                .setAction(VoiceAssistantService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        }

        private fun stopBallService() {
            val intent = Intent(requireContext(), VoiceAssistantService::class.java)
                .setAction(VoiceAssistantService.ACTION_STOP)
            requireContext().startService(intent)
        }

        companion object {
            /** Kokoro v1_1 的 103 个 speaker 描述（基于 sherpa PR #1942）。
             * 中文音色没有官方名称，只有内部编号 zf/zm。 */
            private fun speakerName(sid: Int): String {
                return when {
                    sid == 0 -> "美式女声 #1 (af_maple)"
                    sid == 1 -> "美式女声 #2 (af_sol)"
                    sid == 2 -> "英式女声 #1 (bf_vale)"
                    sid in 3..57 -> "中文女声 #${sid - 2}"
                    sid in 58..102 -> "中文男声 #${sid - 57}"
                    else -> "未知"
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // ★ 迁移：旧版 EditTextPreference 存 String，新版 SeekBarPreference 要 Integer
            //   检测到 String 就转成 Int 重存，避免 ClassCastException
            val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val sidKey = getString(R.string.pref_tts_sid)
            val old = sp.all[sidKey]
            if (old is String) {
                val sidInt = old.toIntOrNull() ?: 3
                sp.edit().putInt(sidKey, sidInt).apply()
            }
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // ★ 悬浮球开关：开启时启动后台服务，关闭时停止
            findPreference<Preference>(getString(R.string.pref_floating_ball))?.setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as? Boolean ?: false
                if (on) {
                    // ★ 权限引导：悬浮窗 → 通知 → 启动服务
                    if (!Settings.canDrawOverlays(requireContext())) {
                        AppLog.i("Settings", "悬浮窗权限未授予，引导去设置")
                        Toast.makeText(requireContext(), "需授权「显示在其他应用上层」才能显示悬浮球", Toast.LENGTH_LONG).show()
                        overlaySettingsLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${requireContext().packageName}")
                            }
                        )
                        return@setOnPreferenceChangeListener false  // 开关不切，等授权后重新检查
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            AppLog.i("Settings", "请求通知权限")
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@setOnPreferenceChangeListener false
                        }
                    }
                    proceedStart()
                } else {
                    stopBallService()
                }
                true
            }

            // ★ sid 滑块联动：实时显示当前音色名称
            val sidPref = findPreference<SeekBarPreference>(getString(R.string.pref_tts_sid))
            sidPref?.let { pref ->
                val updateSidSummary = { value: Int ->
                    pref.summary = "sid=$value ${speakerName(value)}"
                }
                updateSidSummary(pref.value ?: 3)
                pref.setOnPreferenceChangeListener { _, newValue ->
                    updateSidSummary((newValue as? Int) ?: 3)
                    true
                }
            }

            // ★ 音色预览
            findPreference<Preference>(getString(R.string.pref_voice_preview))?.setOnPreferenceClickListener {
                if (previewing) return@setOnPreferenceClickListener true
                onPreviewClick()
                true
            }
        }

        private fun onPreviewClick() {
            val ctx = requireContext()
            val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            val sid = sp.getInt(getString(R.string.pref_tts_sid), 3)
            // ★ pref_tts_speed 是 SeekBarPreference，存的是 Integer，不能用 getString
            val speed = sp.getInt(getString(R.string.pref_tts_speed), 10) / 10.0f

            previewing = true
            showLoading(true)
            // 提示文本：含中英文+数字，能测出音色全貌
            val sample = "你好，我是灵犀语音助手。今天是2024年8月11日，The weather is nice today."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 延迟初始化 TTS（首次约 3 秒）
                    if (ttsEngine == null) {
                        ttsEngine = TtsEngine(ctx)
                    }
                    val engine = ttsEngine ?: return@launch
                    // 同步生成完整音频
                    val (samples, sampleRate) = engine.generateSync(sample, sid, speed)
                        ?: return@launch
                    // 播放
                    playAudio(samples, sampleRate)
                } catch (e: Throwable) {
                    AppLog.e("Settings", "音色预览失败", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        previewing = false
                        showLoading(false)
                    }
                }
            }
        }

        private fun playAudio(samples: FloatArray, sampleRate: Int) {
            audioTrack?.runCatching { stop(); release() }
            // ★ AudioTrack buffer 只是流式缓冲（不需要容下全部音频）
            //   bufferSizeInBytes 参数要字节数，不是 float 个数
            //   用 getMinBufferSize 即可，太小会导致 underrun
            val bufLength = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(4096)  // 最小 4KB 避免无效值
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build(),
                bufLength, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            // ★ 先写入一小块数据再 play，避免启动瞬态噪声
            val initialChunk = minOf(samples.size, 2048)  // 写入 2048 个 float 作为启动缓冲
            track.write(samples, 0, initialChunk, AudioTrack.WRITE_BLOCKING)
            track.play()
            // 流式写入剩余数据
            if (initialChunk < samples.size) {
                var pos = initialChunk
                val chunkSize = 4096
                while (pos < samples.size) {
                    val n = minOf(chunkSize, samples.size - pos)
                    track.write(samples, pos, n, AudioTrack.WRITE_BLOCKING)
                    pos += n
                }
            }
            // 等播放完成
            Thread.sleep(200)
            track.stop()
            track.release()
            audioTrack = null
        }

        private fun showLoading(show: Boolean) {
            val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val sid = sp.getInt(getString(R.string.pref_tts_sid), 3)
            findPreference<Preference>(getString(R.string.pref_voice_preview))?.summary =
                if (show) "正在生成 ${speakerName(sid)}...（首次需加载模型约3秒）"
                else "点击试听 sid=$sid ${speakerName(sid)}"
        }

        override fun onDestroy() {
            super.onDestroy()
            audioTrack?.runCatching { stop(); release() }
            ttsEngine?.release()
        }
    }
}
