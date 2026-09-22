package com.pairplay.app.music

import android.service.notification.NotificationListenerService

/**
 * 알림을 읽기 위한 서비스가 아니다.
 *
 * `MediaSessionManager.getActiveSessions()` 를 호출하려면 앱이
 * NotificationListenerService 를 가지고 있고 사용자가 그것을 활성화해야 한다는
 * 안드로이드 규칙 때문에 존재한다. 알림 내용은 읽지도, 저장하지도, 보내지도 않는다.
 */
class PairPlayNotificationListener : NotificationListenerService()
