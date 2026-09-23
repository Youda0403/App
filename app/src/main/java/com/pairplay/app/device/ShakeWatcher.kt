package com.pairplay.app.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * 휴대폰을 흔들었는지 알려 준다.
 *
 * 가속도 센서만 쓴다. 추가 권한이 필요 없고, 배터리도 거의 쓰지 않는 주기로 읽는다.
 * 센서가 없는 기기에서는 조용히 아무 일도 하지 않는다.
 */
class ShakeWatcher(
    private val context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lastShakeMs = 0L

    fun start() {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (manager == null) {
            Log.i(TAG, "센서를 쓸 수 없어 흔들기 반응을 사용하지 않습니다.")
            return
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Log.i(TAG, "가속도 센서가 없어 흔들기 반응을 사용하지 않습니다.")
            return
        }
        sensorManager = manager
        // UI 주기면 충분하다. 더 자주 읽어도 흔들림 판정이 나아지지 않고 배터리만 먹는다.
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return

        // 중력을 뺀 나머지 크기가 흔들림의 세기다.
        val magnitude = sqrt(
            values[0] * values[0] + values[1] * values[1] + values[2] * values[2]
        ) - SensorManager.GRAVITY_EARTH

        if (magnitude < SHAKE_THRESHOLD) return

        val now = SystemClock.elapsedRealtime()
        // 한 번 흔들면 여러 번 걸린다. 잠깐은 다시 세지 않는다.
        if (now - lastShakeMs < SHAKE_COOLDOWN_MS) return
        lastShakeMs = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "ShakeWatcher"

        /**
         * 이 정도(m/s^2)를 넘으면 흔든 것으로 본다.
         * 주머니에서 걷는 정도로는 걸리지 않고, 일부러 흔들면 걸리는 값이다.
         */
        private const val SHAKE_THRESHOLD = 12f

        /** 한 번 흔든 뒤 이만큼은 다시 세지 않는다. */
        private const val SHAKE_COOLDOWN_MS = 1_200L
    }
}
