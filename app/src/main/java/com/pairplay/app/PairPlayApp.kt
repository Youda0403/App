package com.pairplay.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class PairPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createOverlayNotificationChannel()
    }

    /**
     * 오버레이가 켜져 있는 동안 띄우는 상시 알림용 채널.
     * minSdk 26 이라 채널은 항상 필요하다.
     */
    private fun createOverlayNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_OVERLAY,
            getString(R.string.channel_overlay_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_overlay_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_OVERLAY = "overlay_status"
    }
}
