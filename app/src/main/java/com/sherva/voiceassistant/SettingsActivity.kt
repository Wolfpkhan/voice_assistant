package com.sherva.voiceassistant

import android.content.Context
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
            // ★ 缓冲区加大到最小值的 4 倍，避免首帧 underrun 产生哒哒声
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufLength = (minBuf * 4).coerceAtLeast(samples.size)
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
            // ★ 先写入部分数据再 play，避免启动瞬态噪声
            val initialChunk = minOf(samples.size, bufLength / 2)
            track.write(samples, 0, initialChunk, AudioTrack.WRITE_BLOCKING)
            track.play()
            // 写入剩余数据
            if (initialChunk < samples.size) {
                track.write(samples, initialChunk, samples.size - initialChunk, AudioTrack.WRITE_BLOCKING)
            }
            // 等播放完成
            Thread.sleep(200)
            track.stop()
            track.release()
            audioTrack = null
        }

        private fun showLoading(show: Boolean) {
            // 简单方案：改 summary 提示
            findPreference<Preference>(getString(R.string.pref_voice_preview))?.summary =
                if (show) "正在生成...（首次需加载模型约3秒）"
                else "点击用当前 sid 生成并播放一句示例"
        }

        override fun onDestroy() {
            super.onDestroy()
            audioTrack?.runCatching { stop(); release() }
            ttsEngine?.release()
        }
    }
}
