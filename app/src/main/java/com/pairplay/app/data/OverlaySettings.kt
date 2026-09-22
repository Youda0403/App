package com.pairplay.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 오버레이 표시 설정. 캐릭터/장면 같은 구조화된 데이터와 달리
 * 단순 값이라 DataStore 에 둔다.
 */
data class OverlaySettings(
    val mode: OverlayMode = OverlayMode.PAIR,

    /** 캐릭터 크기 배율(%). 캐릭터별 displayHeightDp 에 곱해진다. */
    val scalePercent: Int = 100,

    /** 불투명도(%). 창이 아니라 뷰에 적용해 Android 12 터치 규칙과 얽히지 않게 한다. */
    val opacityPercent: Int = 100,

    val positionAX: Int = UNSET_POSITION,
    val positionAY: Int = UNSET_POSITION,
    val positionBX: Int = UNSET_POSITION,
    val positionBY: Int = UNSET_POSITION,

    /** 접기: 캐릭터를 작은 손잡이로 줄여 둔 상태. */
    val collapsed: Boolean = false,

    /** 두 캐릭터를 한꺼번에 끌지, 따로 끌지. */
    val linkedDrag: Boolean = false,

    /** 음악 재생 상태에 반응할지. 권한이 없으면 무시된다. */
    val musicReactionEnabled: Boolean = true,

    /** 하트·음표 같은 표시를 띄울지. */
    val effectsEnabled: Boolean = true,

    /** 머리 위 기분 표시(말풍선)를 띄울지. */
    val bubblesEnabled: Boolean = true,

    /**
     * 얼마나 활발하게 돌아다닐지(%).
     * 이동 거리, 장면이 나오는 빈도, 동작 속도에 함께 반영된다.
     */
    val activityPercent: Int = 50,

    /** 이 시각(epoch ms)까지 숨긴다. 0 이면 숨김 없음. */
    val hiddenUntilMillis: Long = 0L,

    val onboardingCompleted: Boolean = false
) {
    val scale: Float get() = scalePercent / 100f
    val activity: Float get() = (activityPercent / 100f).coerceIn(0f, 1f)
    val opacity: Float get() = opacityPercent / 100f

    fun isHiddenAt(now: Long): Boolean = hiddenUntilMillis > now

    companion object {
        const val UNSET_POSITION = Int.MIN_VALUE
        const val MIN_SCALE_PERCENT = 40
        const val MAX_SCALE_PERCENT = 250
        const val MIN_OPACITY_PERCENT = 20
        const val MAX_OPACITY_PERCENT = 100
    }
}

private val Context.overlayDataStore: DataStore<Preferences> by preferencesDataStore("overlay_settings")

class OverlaySettingsStore(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val SCALE = intPreferencesKey("scale_percent")
        val OPACITY = intPreferencesKey("opacity_percent")
        val POS_AX = intPreferencesKey("pos_ax")
        val POS_AY = intPreferencesKey("pos_ay")
        val POS_BX = intPreferencesKey("pos_bx")
        val POS_BY = intPreferencesKey("pos_by")
        val COLLAPSED = booleanPreferencesKey("collapsed")
        val LINKED_DRAG = booleanPreferencesKey("linked_drag")
        val MUSIC = booleanPreferencesKey("music_reaction")
        val EFFECTS = booleanPreferencesKey("effects_enabled")
        val BUBBLES = booleanPreferencesKey("bubbles_enabled")
        val ACTIVITY = intPreferencesKey("activity_percent")
        val HIDDEN_UNTIL = longPreferencesKey("hidden_until")
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
    }

    val settings: Flow<OverlaySettings> = context.overlayDataStore.data.map { prefs ->
        val defaults = OverlaySettings()
        OverlaySettings(
            mode = runCatching { OverlayMode.valueOf(prefs[Keys.MODE] ?: defaults.mode.name) }
                .getOrDefault(defaults.mode),
            scalePercent = prefs[Keys.SCALE] ?: defaults.scalePercent,
            opacityPercent = prefs[Keys.OPACITY] ?: defaults.opacityPercent,
            positionAX = prefs[Keys.POS_AX] ?: defaults.positionAX,
            positionAY = prefs[Keys.POS_AY] ?: defaults.positionAY,
            positionBX = prefs[Keys.POS_BX] ?: defaults.positionBX,
            positionBY = prefs[Keys.POS_BY] ?: defaults.positionBY,
            collapsed = prefs[Keys.COLLAPSED] ?: defaults.collapsed,
            linkedDrag = prefs[Keys.LINKED_DRAG] ?: defaults.linkedDrag,
            musicReactionEnabled = prefs[Keys.MUSIC] ?: defaults.musicReactionEnabled,
            effectsEnabled = prefs[Keys.EFFECTS] ?: defaults.effectsEnabled,
            bubblesEnabled = prefs[Keys.BUBBLES] ?: defaults.bubblesEnabled,
            activityPercent = prefs[Keys.ACTIVITY] ?: defaults.activityPercent,
            hiddenUntilMillis = prefs[Keys.HIDDEN_UNTIL] ?: defaults.hiddenUntilMillis,
            onboardingCompleted = prefs[Keys.ONBOARDING] ?: defaults.onboardingCompleted
        )
    }

    suspend fun setMode(mode: OverlayMode) = edit { it[Keys.MODE] = mode.name }

    suspend fun setScalePercent(percent: Int) = edit {
        it[Keys.SCALE] = percent.coerceIn(
            OverlaySettings.MIN_SCALE_PERCENT,
            OverlaySettings.MAX_SCALE_PERCENT
        )
    }

    suspend fun setOpacityPercent(percent: Int) = edit {
        it[Keys.OPACITY] = percent.coerceIn(
            OverlaySettings.MIN_OPACITY_PERCENT,
            OverlaySettings.MAX_OPACITY_PERCENT
        )
    }

    suspend fun setPositionA(x: Int, y: Int) = edit {
        it[Keys.POS_AX] = x
        it[Keys.POS_AY] = y
    }

    suspend fun setPositionB(x: Int, y: Int) = edit {
        it[Keys.POS_BX] = x
        it[Keys.POS_BY] = y
    }

    suspend fun setCollapsed(collapsed: Boolean) = edit { it[Keys.COLLAPSED] = collapsed }

    suspend fun setLinkedDrag(linked: Boolean) = edit { it[Keys.LINKED_DRAG] = linked }

    suspend fun setMusicReaction(enabled: Boolean) = edit { it[Keys.MUSIC] = enabled }

    suspend fun setEffectsEnabled(enabled: Boolean) = edit { it[Keys.EFFECTS] = enabled }

    suspend fun setBubblesEnabled(enabled: Boolean) = edit { it[Keys.BUBBLES] = enabled }

    suspend fun setActivityPercent(percent: Int) = edit {
        it[Keys.ACTIVITY] = percent.coerceIn(0, 100)
    }

    suspend fun setHiddenUntil(epochMillis: Long) = edit { it[Keys.HIDDEN_UNTIL] = epochMillis }

    suspend fun setOnboardingCompleted(done: Boolean) = edit { it[Keys.ONBOARDING] = done }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.overlayDataStore.edit(block)
    }
}
