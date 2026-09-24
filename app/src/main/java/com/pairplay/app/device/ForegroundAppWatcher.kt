package com.pairplay.app.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * 지금 앞에 떠 있는 앱이 무엇인지 알려 준다.
 *
 * 안드로이드의 '사용 정보 접근' 권한을 쓴다. 사용자가 설정에서 직접 허락해야 하고,
 * 허락하지 않았으면 아무 일도 하지 않는다.
 *
 * 알 수 있는 것은 **앱 이름(패키지)** 뿐이다. 그 앱 안에서 무엇을 보고 있는지는 알 수
 * 없고 알려고 하지도 않는다. 알아낸 이름은 폰 밖으로 보내지 않는다.
 *
 * 앱이 바뀌는 순간을 바로 알려 주는 방법은 없어서, 짧은 간격으로 확인한다.
 * 그래서 앱을 바꾼 뒤 1초 남짓 늦게 알아챈다. 화면이 꺼져 있으면 확인하지 않는다.
 */
class ForegroundAppWatcher(
    private val context: Context,
    private val onChanged: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastPackage: String? = null
    private var lastQueryAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastQueryAt = System.currentTimeMillis() - LOOKBACK_MS
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        lastPackage = null
    }

    private fun check() {
        // 권한은 서비스가 도는 중에도 켜고 끌 수 있으니 매번 확인한다. 가벼운 확인이다.
        if (!hasPermission(context)) return
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return

        val now = System.currentTimeMillis()
        val events = try {
            usage.queryEvents(lastQueryAt - OVERLAP_MS, now)
        } catch (e: SecurityException) {
            Log.w(TAG, "사용 정보를 읽을 수 없습니다", e)
            return
        }
        lastQueryAt = now

        // 이 구간에서 가장 마지막으로 앞에 나온 앱이 지금 앱이다.
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        if (latest == null || latest == lastPackage) return
        lastPackage = latest
        onChanged(latest)
    }

    companion object {
        private const val TAG = "ForegroundAppWatcher"

        /** 얼마나 자주 확인할지. 짧을수록 빨리 알아채지만 배터리를 조금 더 쓴다. */
        private const val POLL_MS = 1_200L

        /** 처음 시작할 때 이만큼 거슬러 올라가 지금 앱을 찾는다. */
        private const val LOOKBACK_MS = 60_000L

        /** 확인 구간을 살짝 겹쳐, 경계에서 앱 전환을 놓치지 않게 한다. */
        private const val OVERLAP_MS = 1_000L

        /** '사용 정보 접근' 을 허락했는지. */
        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** '사용 정보 접근' 설정 화면. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
}
