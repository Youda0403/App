package com.pairplay.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.pairplay.app.music.MusicWatcher

/**
 * 권한 화면으로 보내고 돌아오면 상태를 다시 읽는 런처들.
 * 오버레이/알림 접근은 런타임 권한이 아니라 설정 화면을 거쳐야 해서 따로 묶었다.
 */
class PermissionLaunchers(
    private val context: Context,
    private val overlayLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    private val notificationAccessLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    private val postNotificationLauncher: ManagedActivityResultLauncher<String, Boolean>
) {

    /** '다른 앱 위에 표시' 설정 화면을 연다. */
    fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        overlayLauncher.launch(intent)
    }

    /** 음악 반응에 필요한 알림 접근 설정 화면을 연다. */
    fun requestNotificationAccess() {
        notificationAccessLauncher.launch(MusicWatcher.notificationAccessSettingsIntent())
    }

    /**
     * Android 13 이상에서 상시 알림을 띄우기 위한 런타임 권한.
     * 거절해도 오버레이는 동작하고, 알림에서 중지하는 기능만 쓸 수 없다.
     */
    fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val needsPostNotifications: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

@Composable
fun rememberPermissionLaunchers(onChanged: () -> Unit): PermissionLaunchers {
    val context = LocalContext.current

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onChanged() }

    val notificationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onChanged() }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onChanged() }

    return PermissionLaunchers(
        context = context,
        overlayLauncher = overlayLauncher,
        notificationAccessLauncher = notificationAccessLauncher,
        postNotificationLauncher = postNotificationLauncher
    )
}
