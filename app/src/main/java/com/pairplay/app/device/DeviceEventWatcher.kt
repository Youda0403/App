package com.pairplay.app.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.pairplay.app.engine.DeviceEvent

/**
 * 충전기·이어폰·잠금 해제·소리 크기·배터리처럼 휴대폰에서 벌어지는 일을 알려 준다.
 *
 * 전부 추가 권한 없이 알 수 있는 것들이다.
 * 방송은 앱이 켜져 있는 동안에만 받으면 되므로 코드에서 등록한다.
 * (매니페스트에 등록하면 앱이 꺼져 있을 때도 깨어나 배터리를 쓴다)
 */
class DeviceEventWatcher(
    private val context: Context,
    private val onEvent: (DeviceEvent) -> Unit
) {

    private var registered = false

    /** 직전 배터리 상태. 같은 알림이 연달아 오는 것을 걸러 낸다. */
    private var lowNotified = false
    private var fullNotified = false

    /** 직전 미디어 볼륨. 올렸는지 내렸는지를 가리는 데 쓴다. */
    private var lastVolume = -1

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 소리 크기가 바뀌었는지 본다.
     *
     * 볼륨 버튼을 직접 가로챌 수는 없다(그건 화면 맨 앞에 있는 앱만 할 수 있다).
     * 대신 설정값이 바뀌는 것을 지켜보면, 어떤 방법으로 바꾸든 알 수 있다.
     */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
            val before = lastVolume
            lastVolume = now
            if (before < 0 || before == now) return
            onEvent(if (now > before) DeviceEvent.VOLUME_UP else DeviceEvent.VOLUME_DOWN)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> DeviceEvent.CHARGER_ON
                Intent.ACTION_POWER_DISCONNECTED -> DeviceEvent.CHARGER_OFF
                Intent.ACTION_USER_PRESENT -> DeviceEvent.UNLOCKED
                Intent.ACTION_BATTERY_CHANGED -> batteryEvent(intent)
                Intent.ACTION_HEADSET_PLUG -> {
                    // state 는 0 이 뺀 것, 1 이 꽂은 것이다.
                    if (intent.getIntExtra("state", 0) == 1) {
                        DeviceEvent.HEADSET_ON
                    } else {
                        DeviceEvent.HEADSET_OFF
                    }
                }

                else -> null
            } ?: return
            onEvent(event)
        }
    }

    /**
     * 배터리 상태가 바뀌었다. 부족해지거나 다 찼을 때만 한 번씩 알린다.
     *
     * 이 방송은 몇 초마다 오므로, 같은 상태에서 계속 반응하면 곤란하다.
     */
    private fun batteryEvent(intent: Intent): DeviceEvent? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = level * 100 / scale

        if (percent <= LOW_PERCENT) {
            if (lowNotified) return null
            lowNotified = true
            fullNotified = false
            return DeviceEvent.BATTERY_LOW
        }

        // 부족 상태에서 충분히 벗어나야 다음번에 다시 알린다.
        if (percent > LOW_PERCENT + HYSTERESIS) lowNotified = false

        if (percent >= FULL_PERCENT) {
            if (fullNotified) return null
            fullNotified = true
            return DeviceEvent.BATTERY_FULL
        }
        if (percent < FULL_PERCENT - HYSTERESIS) fullNotified = false
        return null
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            // Android 14 부터는 이 앱 전용인지(NOT_EXPORTED) 밝혀야 한다.
            // 여기서 듣는 것은 전부 시스템이 보내는 방송이라 바깥에 열어 둘 필요가 없다.
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "기기 반응을 등록하지 못했습니다", e)
        }

        lastVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "소리 크기를 지켜볼 수 없습니다", e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "이미 해제된 수신기", e)
        }
        registered = false

        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "이미 해제된 소리 감시", e)
        }
    }

    companion object {
        private const val TAG = "DeviceEventWatcher"

        /** 이 아래로 떨어지면 기운이 빠진다. */
        private const val LOW_PERCENT = 15

        /** 이 위로 올라오면 다 찼다고 본다. */
        private const val FULL_PERCENT = 100

        /** 경계에서 알림이 왔다 갔다 하지 않도록 두는 여유(%). */
        private const val HYSTERESIS = 5
    }
}
