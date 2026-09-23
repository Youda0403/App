package com.pairplay.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pairplay.app.PairPlayApp
import com.pairplay.app.R
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.OverlayMode
import com.pairplay.app.data.OverlaySettings
import com.pairplay.app.data.OverlaySettingsStore
import com.pairplay.app.data.PairPlayDatabase
import com.pairplay.app.device.DeviceEventWatcher
import com.pairplay.app.device.ShakeWatcher
import com.pairplay.app.engine.DeviceEvent
import com.pairplay.app.music.MusicWatcher
import com.pairplay.app.ui.MainActivity
import com.pairplay.app.widget.PairPlayWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 캐릭터가 화면에 떠 있는 동안 살아 있는 포그라운드 서비스.
 *
 * Android 14 이상에서는 포그라운드 서비스 유형을 반드시 선언해야 하며,
 * 오버레이 캐릭터는 기존 유형 어디에도 맞지 않아 specialUse 를 쓴다.
 * (매니페스트에 사유를 적어 두었다. 개인 설치용이라 심사 대상은 아니다.)
 */
class OverlayService : LifecycleService() {

    /** 오버레이가 화면을 그리는 데 필요한 모든 데이터. */
    private data class ObservedState(
        val pair: com.pairplay.app.data.PairEntity?,
        val characters: List<CharacterEntity>,
        val settings: OverlaySettings,
        val scenes: List<com.pairplay.app.data.SceneEntity>
    )

    private lateinit var settingsStore: OverlaySettingsStore
    private var controller: OverlayController? = null
    private var musicWatcher: MusicWatcher? = null
    private var shakeWatcher: ShakeWatcher? = null
    private var deviceEventWatcher: DeviceEventWatcher? = null
    private var lastSettings: OverlaySettings = OverlaySettings()

    override fun onCreate() {
        super.onCreate()
        settingsStore = OverlaySettingsStore(applicationContext)

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "오버레이 권한이 없어 서비스를 시작하지 않습니다.")
            stopSelf()
            return
        }

        startForegroundSafely()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = OverlayController(
            context = this,
            windowManager = windowManager,
            onHideRequested = { hideIndefinitely() },
            onPositionPersist = { slot, x, y -> persistPosition(slot, x, y) },
            onSceneChanged = { name ->
                _currentScene.value = name
                // 위젯에도 지금 상황을 알린다. 너무 잦은 갱신은 위젯 쪽에서 걸러 낸다.
                PairPlayWidgetProvider.refresh(applicationContext)
            }
        ).also { it.start() }

        observeData()
        startMusicWatcher()
        _isRunning.value = true
        PairPlayWidgetProvider.refresh(applicationContext, force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_HIDE -> toggleHide()
            ACTION_RUN_SCENE -> {
                val sceneId = intent.getLongExtra(EXTRA_SCENE_ID, -1L)
                if (sceneId >= 0) controller?.runSceneNow(sceneId)
            }
            ACTION_HIDE_FOR_MINUTES -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 10)
                hideForMinutes(minutes)
            }
        }

        // 시스템이 서비스를 죽였을 때 인텐트 없이 다시 살아나도 동작하게 한다.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        _isRunning.value = false
        _currentScene.value = null
        PairPlayWidgetProvider.refresh(applicationContext, force = true)
        musicWatcher?.stop()
        musicWatcher = null
        shakeWatcher?.stop()
        shakeWatcher = null
        deviceEventWatcher?.stop()
        deviceEventWatcher = null
        controller?.release()
        controller = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 데이터

    private fun observeData() {
        val database = PairPlayDatabase.get(applicationContext)

        lifecycleScope.launch {
            combine(
                database.pairDao().observeActive(),
                database.characterDao().observeAll(),
                settingsStore.settings,
                database.sceneDao().observeAll()
            ) { pair, characters, settings, scenes ->
                ObservedState(pair, characters, settings, scenes)
            }.distinctUntilChanged().collect { (pair, characters, settings, scenes) ->
                lastSettings = settings
                val byId = characters.associateBy { it.id }

                val a: CharacterEntity?
                val b: CharacterEntity?
                if (pair != null) {
                    a = byId[pair.characterAId]
                    b = pair.characterBId?.let { byId[it] }
                } else {
                    // 짝을 만들지 않았어도 등록된 캐릭터가 있으면 띄운다.
                    a = characters.firstOrNull()
                    b = characters.getOrNull(1)
                }

                controller?.updateSettings(settings)
                controller?.setUserScenes(scenes)
                applyDeviceWatchers(settings.deviceReactionsEnabled)
                PairPlayWidgetProvider.refresh(applicationContext, force = true)
                controller?.setCharacters(
                    a = a,
                    b = if (settings.mode == OverlayMode.PAIR) b else null,
                    pair = pair
                )
                updateNotification(settings)
            }
        }
    }

    /**
     * 휴대폰에서 벌어지는 일에 반응하기 위한 감시들.
     * 추가 권한이 필요 없고, 서비스가 떠 있는 동안에만 동작한다.
     */
    private fun applyDeviceWatchers(enabled: Boolean) {
        if (enabled) {
            if (shakeWatcher == null) {
                shakeWatcher = ShakeWatcher(this) {
                    controller?.onDeviceEvent(DeviceEvent.SHAKE)
                }.also { it.start() }
            }
            if (deviceEventWatcher == null) {
                deviceEventWatcher = DeviceEventWatcher(this) { event ->
                    controller?.onDeviceEvent(event)
                }.also { it.start() }
            }
        } else {
            // 꺼 두었으면 센서도 함께 멈춘다. 켜 둔 채로 무시하면 배터리만 쓴다.
            shakeWatcher?.stop()
            shakeWatcher = null
            deviceEventWatcher?.stop()
            deviceEventWatcher = null
        }
    }

    private fun startMusicWatcher() {
        musicWatcher = MusicWatcher(this) { playing ->
            controller?.setMusicPlaying(playing)
        }.also { it.start() }
    }

    private fun persistPosition(slot: CharacterWindow.Slot, x: Int, y: Int) {
        lifecycleScope.launch {
            when (slot) {
                CharacterWindow.Slot.A -> settingsStore.setPositionA(x, y)
                CharacterWindow.Slot.B -> settingsStore.setPositionB(x, y)
            }
        }
    }

    // ------------------------------------------------------------------ 숨김

    private fun hideIndefinitely() {
        lifecycleScope.launch { settingsStore.setHiddenUntil(Long.MAX_VALUE) }
    }

    private fun hideForMinutes(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        lifecycleScope.launch { settingsStore.setHiddenUntil(until) }
    }

    private fun toggleHide() {
        lifecycleScope.launch {
            val hidden = lastSettings.isHiddenAt(System.currentTimeMillis())
            settingsStore.setHiddenUntil(if (hidden) 0L else Long.MAX_VALUE)
        }
    }

    // ------------------------------------------------------------------ 알림

    private fun startForegroundSafely() {
        val notification = buildNotification(OverlaySettings())
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } catch (e: IllegalStateException) {
            // Android 12+ 에서 백그라운드에서 시작하려 한 경우 등.
            Log.w(TAG, "포그라운드로 전환하지 못했습니다", e)
            stopSelf()
        }
    }

    private fun updateNotification(settings: OverlaySettings) {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(settings))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 가 거부된 경우. 오버레이 자체는 계속 동작한다.
            Log.i(TAG, "알림 권한이 없어 상시 알림을 갱신하지 않습니다.")
        }
    }

    private fun buildNotification(settings: OverlaySettings): Notification {
        val hidden = settings.isHiddenAt(System.currentTimeMillis())

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val toggleHide = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_HIDE),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PairPlayApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (hidden) R.string.notification_text_hidden else R.string.notification_text_visible
                )
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 아이콘을 0 으로 넘기면 일부 제조사 알림창에서 버튼 자체가 그려지지 않는다.
            // 실제 아이콘을 넘겨야 '숨기기'와 '중지'가 확실히 보인다.
            .addAction(
                if (hidden) R.drawable.ic_action_show else R.drawable.ic_action_hide,
                getString(if (hidden) R.string.action_show else R.string.action_hide),
                toggleHide
            )
            .addAction(R.drawable.ic_action_stop, getString(R.string.action_stop), stop)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.pairplay.app.STOP"
        const val ACTION_TOGGLE_HIDE = "com.pairplay.app.TOGGLE_HIDE"
        const val ACTION_HIDE_FOR_MINUTES = "com.pairplay.app.HIDE_FOR_MINUTES"
        const val ACTION_RUN_SCENE = "com.pairplay.app.RUN_SCENE"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SCENE_ID = "scene_id"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /** 지금 도는 상황극 이름. 앱 화면에서 확인용으로 보여 준다. */
        private val _currentScene = MutableStateFlow<String?>(null)
        val currentScene: StateFlow<String?> = _currentScene

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        /** 장면 편집기에서 만든 장면을 지금 화면에서 보여 준다. */
        fun runScene(context: Context, sceneId: Long) {
            if (!isRunning.value) return
            context.startService(
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_RUN_SCENE)
                    .putExtra(EXTRA_SCENE_ID, sceneId)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
