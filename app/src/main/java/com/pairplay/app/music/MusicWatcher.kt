package com.pairplay.app.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 활성 미디어 세션을 보고 "지금 음악이 재생 중인가"만 알려준다.
 *
 * 중요한 제약: [MediaSessionManager.getActiveSessions] 는 이 앱의
 * NotificationListenerService 가 사용자에 의해 **활성화되어 있을 때만** 호출할 수 있다.
 * 활성화되어 있지 않으면 호출 자체를 하지 않고 음악 반응을 꺼진 상태로 둔다.
 *
 * 박자 분석은 하지 않는다. 다른 앱의 오디오를 캡처하는 것은 재생 앱의 허용 여부에
 * 좌우되므로 1차 범위에서 제외했다.
 */
class MusicWatcher(
    private val context: Context,
    private val onPlayingChanged: (Boolean) -> Unit
) {

    private var sessionManager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var playing = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshPlayingState()
        }

        override fun onSessionDestroyed() {
            refreshPlayingState()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { newControllers ->
            bindControllers(newControllers ?: emptyList())
            refreshPlayingState()
        }

    fun start() {
        if (!isNotificationAccessGranted(context)) {
            Log.i(TAG, "알림 접근 권한이 없어 음악 반응을 사용하지 않습니다.")
            return
        }

        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return
        sessionManager = manager

        val listenerComponent = ComponentName(context, PairPlayNotificationListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
            bindControllers(manager.getActiveSessions(listenerComponent))
            refreshPlayingState()
        } catch (e: SecurityException) {
            // 사용자가 방금 권한을 껐거나 시스템이 거부한 경우.
            Log.w(TAG, "미디어 세션에 접근할 수 없습니다", e)
            stop()
        }
    }

    fun stop() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
        sessionManager?.let { manager ->
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        }
        sessionManager = null
    }

    private fun bindControllers(newControllers: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = newControllers
        controllers.forEach { runCatching { it.registerCallback(controllerCallback) } }
    }

    private fun refreshPlayingState() {
        val anyPlaying = controllers.any { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (anyPlaying != playing) {
            playing = anyPlaying
            onPlayingChanged(anyPlaying)
        }
    }

    companion object {
        private const val TAG = "MusicWatcher"

        /** 알림 접근(= 미디어 세션 접근) 권한이 사용자에 의해 켜져 있는지. */
        fun isNotificationAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** 알림 접근 설정 화면으로 보내는 인텐트. */
        fun notificationAccessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
