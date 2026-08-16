package com.sherva.voiceassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.sherva.voiceassistant.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

        /** Kokoro v1_1 的 103 个 speaker 描述（基于 sherpa PR #1942）。
         * 中文音色没有官方名称，只有内部编号 zf/zm。
         * ★ 从 companion 移到 fragment 成员：需要 getString() 走资源做双语。 */
        private fun speakerName(sid: Int): String {
            return when {
                sid == 0 -> getString(R.string.speaker_us_female_1)
                sid == 1 -> getString(R.string.speaker_us_female_2)
                sid == 2 -> getString(R.string.speaker_uk_female_1)
                sid in 3..57 -> getString(R.string.speaker_zh_female, sid - 2)
                sid in 58..102 -> getString(R.string.speaker_zh_male, sid - 57)
                else -> getString(R.string.speaker_unknown)
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

            // ★ 语言切换：AppCompatDelegate.setApplicationLocales（appcompat 1.6 兼容到 API<33，
            //   自动持久化并重建 Activity；API 33+ 走系统 per-app language）
            // ★ 唤醒确认窗口：内部 0.1s 单位（seek=16 → 1.6s），summary 换算显示人类可读值
            findPreference<SeekBarPreference>(getString(R.string.pref_kws_confirm_ds))?.apply {
                summaryProvider = androidx.preference.Preference.SummaryProvider<SeekBarPreference> { p ->
                    "%.1f s".format(p.value / 10.0)
                }
            }
            findPreference<ListPreference>(getString(R.string.pref_app_language))?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val locales = when (newValue as? String) {
                        "zh" -> androidx.core.os.LocaleListCompat.forLanguageTags("zh")
                        "en" -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
                        else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()  // 跟随系统
                    }
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                    true
                }
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

            // ★ 设置导出/导入：SharedPreferences → JSON，方便换机/重装
            findPreference<Preference>("settings_export")?.setOnPreferenceClickListener {
                exportLauncher.launch("settings_backup_${System.currentTimeMillis()}.json")
                true
            }
            findPreference<Preference>("settings_import")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                true
            }
        }

        // ---------- 设置导入/导出 ----------

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) doExportSettings(uri)
        }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) doImportSettings(uri)
        }

        private fun doExportSettings(uri: Uri) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val root = JSONObject().apply {
                        put("_type", "sherva-settings-backup")
                        put("_version", 1)
                        put("_exportedAt", System.currentTimeMillis())
                        val prefs = JSONObject()
                        sp.all.forEach { (k, v) ->
                            when (v) {
                                is String -> prefs.put(k, v)
                                is Int -> prefs.put(k, v)
                                is Long -> prefs.put(k, v)
                                is Float -> prefs.put(k, v)
                                is Boolean -> prefs.put(k, v)
                            }
                        }
                        put("prefs", prefs)
                    }
                    requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(root.toString(2).toByteArray(Charsets.UTF_8))
                    } ?: throw RuntimeException(getString(R.string.err_write_uri, uri.toString()))
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_settings_exported), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_settings_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        private fun doImportSettings(uri: Uri) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                        ins.readBytes().toString(Charsets.UTF_8)
                    } ?: throw RuntimeException(getString(R.string.err_read_uri, uri.toString()))
                    val root = JSONObject(json)
                    if (root.optString("_type") != "sherva-settings-backup") {
                        throw RuntimeException(getString(R.string.err_invalid_backup))
                    }
                    val prefs = root.optJSONObject("prefs") ?: throw RuntimeException(getString(R.string.err_backup_missing_prefs))
                    val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val ed = sp.edit().clear()  // ★ 先清空再导入：以备份为准，避免残留
                    var count = 0
                    prefs.keys().forEach { k ->
                        when (val v = prefs.get(k)) {
                            is String -> { ed.putString(k, v); count++ }
                            is Int -> { ed.putInt(k, v); count++ }
                            is Long -> { ed.putLong(k, v); count++ }
                            is Float -> { ed.putFloat(k, v); count++ }
                            is Boolean -> { ed.putBoolean(k, v); count++ }
                        }
                    }
                    ed.apply()
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            requireContext(), getString(R.string.toast_settings_imported, count), android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_settings_import_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
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
            val sample = getString(R.string.voice_preview_sample)

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
                if (show) getString(R.string.voice_preview_generating, speakerName(sid))
                else getString(R.string.voice_preview_idle, sid, speakerName(sid))
        }

        override fun onDestroy() {
            super.onDestroy()
            audioTrack?.runCatching { stop(); release() }
            ttsEngine?.release()
        }
    }
}
