package com.pairplay.app.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.pairplay.app.engine.DeviceEvent

/**
 * 충전기·이어폰·잠금 해제처럼 휴대폰에서 벌어지는 일을 알려 준다.
 *
 * 전부 추가 권한 없이 받을 수 있는 방송이다.
 * 이 방송들은 앱이 켜져 있는 동안에만 받으면 되므로 코드에서 등록한다.
 * (매니페스트에 등록하면 앱이 꺼져 있을 때도 깨어나 배터리를 쓴다)
 */
class DeviceEventWatcher(
    private val context: Context,
    private val onEvent: (DeviceEvent) -> Unit
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> DeviceEvent.CHARGER_ON
                Intent.ACTION_POWER_DISCONNECTED -> DeviceEvent.CHARGER_OFF
                Intent.ACTION_USER_PRESENT -> DeviceEvent.UNLOCKED
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

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_USER_PRESENT)
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
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "이미 해제된 수신기", e)
        }
        registered = false
    }

    companion object {
        private const val TAG = "DeviceEventWatcher"
    }
}
