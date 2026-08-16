package com.sherva.voiceassistant.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ★ 蓝牙麦克风通路管理：让 AudioRecord 从蓝牙耳机取声。
 *
 * 两代 API：
 *  - Android 12+（API 31）：setCommunicationDevice(bluetoothScoDevice) + MODE_IN_COMMUNICATION。
 *    startBluetoothSco() 在新 ROM（vivo Android 16 实测）已被 AudioService 忽略——
 *    dumpsys 显示 mScoAudioState 始终 SCO_STATE_INACTIVE。
 *  - Android 11-：startBluetoothSco() + 等 SCO_AUDIO_STATE_CONNECTED 广播。
 *
 * 代价：HFP 通路激活期间 A2DP 高品质音乐降为通话音质。用完 disconnect 恢复。
 */
object ScoAudioRouter {

    /** 建立蓝牙麦克风通路（阻塞等待，最长 timeoutMs）。幂等：已连接直接返回 true。
     *  生命周期：会话级——WAKE_WORD 连上后整个对话（KWS→ASR→TTS）期间保持，
     *  只有对话彻底停止才 disconnect。 */
    fun connect(context: Context, timeoutMs: Long = 5000): Boolean {
        if (isConnected(context)) {
            com.sherva.voiceassistant.AppLog.i("SCO", "已连接，复用现有通路")
            return true
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ★ 新路径（API 31+）：setCommunicationDevice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: run {
                    com.sherva.voiceassistant.AppLog.i("SCO", "无蓝牙通信设备（availableCommunicationDevices 为空），用机内麦")
                    return false
                }
                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                val ok = am.setCommunicationDevice(bt)
                if (ok) {
                    // 给底层 HFP 建链一点时间（通常 <1s），期间录音路由已生效
                    Thread.sleep(600)
                }
                com.sherva.voiceassistant.AppLog.i("SCO",
                    if (ok) "✓ setCommunicationDevice(${bt.productName}) 成功（HFP 通路激活）"
                    else "setCommunicationDevice(${bt.productName}) 被拒绝")
                ok
            } catch (e: Throwable) {
                com.sherva.voiceassistant.AppLog.i("SCO", "setCommunicationDevice 异常: ${e.message}")
                false
            }
        }

        // ★ 旧路径（API < 31）：startBluetoothSco + 广播等待
        if (!am.isBluetoothScoAvailableOffCall) {
            com.sherva.voiceassistant.AppLog.i("SCO", "isBluetoothScoAvailableOffCall=false，放弃")
            return false
        }
        val oldMode = try { am.mode } catch (_: Exception) { AudioManager.MODE_NORMAL }
        val latch = CountDownLatch(1)
        var connected = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> { connected = true; latch.countDown() }
                    // DISCONNECTED 忽略：发起 start 后系统先报一次当前态（初始状态广播），
                    // 立即 countDown 会造成假超时。只有连上过才算断开。
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {}
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            if (!connected) {
                com.sherva.voiceassistant.AppLog.i("SCO", "首次未连，重试一次…")
                am.stopBluetoothSco()
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                try { latch.await(2000, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            }
        } catch (e: Throwable) {
            com.sherva.voiceassistant.AppLog.i("SCO", "connect 异常: ${e.message}")
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            if (!connected) {
                try { am.mode = oldMode } catch (_: Exception) {}
            }
        }
        com.sherva.voiceassistant.AppLog.i("SCO", if (connected) "✓ SCO 通道已建立" else "✗ SCO 连接超时（${timeoutMs}ms+重试），继续用机内麦")
        return connected
    }

    /** 当前是否已激活蓝牙通信设备（HFP 通路）。 */
    fun isConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val d = am.communicationDevice
            d != null && (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } else {
            am.isBluetoothScoOn
        }
    }

    /** 断开通路（恢复 A2DP 音质）。 */
    fun disconnect(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
                com.sherva.voiceassistant.AppLog.i("SCO", "communicationDevice 已清除（A2DP 恢复）")
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                com.sherva.voiceassistant.AppLog.i("SCO", "SCO 已断开（A2DP 恢复）")
            }
        } catch (e: Throwable) {
            com.sherva.voiceassistant.AppLog.i("SCO", "disconnect 异常: ${e.message}")
        }
    }
}
