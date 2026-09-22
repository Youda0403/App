package com.pairplay.app.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.CharacterRepository
import com.pairplay.app.data.OverlayMode
import com.pairplay.app.data.OverlaySettings
import com.pairplay.app.data.OverlaySettingsStore
import com.pairplay.app.data.PairEntity
import com.pairplay.app.image.ImageImporter
import com.pairplay.app.music.MusicWatcher
import com.pairplay.app.overlay.OverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PairPlayUiState(
    val characters: List<CharacterEntity> = emptyList(),
    val activePair: PairEntity? = null,
    val settings: OverlaySettings = OverlaySettings(),
    val overlayRunning: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null
) {
    val characterA: CharacterEntity?
        get() = activePair?.let { pair -> characters.firstOrNull { it.id == pair.characterAId } }
            ?: characters.firstOrNull()

    val characterB: CharacterEntity?
        get() = activePair?.characterBId?.let { id -> characters.firstOrNull { it.id == id } }
            ?: characters.getOrNull(1).takeIf { activePair == null }
}

class PairPlayViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CharacterRepository(application)
    private val settingsStore = OverlaySettingsStore(application)

    private val permissionState = MutableStateFlow(readPermissionState())
    private val message = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(PairPlayUiState())
    val uiState: StateFlow<PairPlayUiState> = _uiState.asStateFlow()

    /** 권한 상태와 안내 메시지처럼 화면 밖 사정을 한 덩어리로 묶는다. */
    private data class Ambient(
        val canDrawOverlays: Boolean,
        val notificationAccessGranted: Boolean,
        val message: String?
    )

    init {
        viewModelScope.launch {
            repository.ensureDefaultCharacters()
        }
        viewModelScope.launch {
            val ambient = combine(permissionState, message) { permissions, text ->
                Ambient(permissions.first, permissions.second, text)
            }
            combine(
                repository.observeCharacters(),
                repository.observeActivePair(),
                settingsStore.settings,
                OverlayService.isRunning,
                ambient
            ) { characters, pair, settings, running, outside ->
                PairPlayUiState(
                    characters = characters,
                    activePair = pair,
                    settings = settings,
                    overlayRunning = running,
                    canDrawOverlays = outside.canDrawOverlays,
                    notificationAccessGranted = outside.notificationAccessGranted,
                    loading = false,
                    message = outside.message
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // ------------------------------------------------------------------ 권한

    /** 설정 화면에서 돌아왔을 때 권한 상태를 다시 읽는다. */
    fun refreshPermissions() {
        permissionState.value = readPermissionState()
    }

    private fun readPermissionState(): Pair<Boolean, Boolean> {
        val context = getApplication<Application>()
        return Settings.canDrawOverlays(context) to
            MusicWatcher.isNotificationAccessGranted(context)
    }

    // ------------------------------------------------------------------ 캐릭터

    fun addCharacter(uri: Uri, name: String) {
        viewModelScope.launch {
            when (val result = repository.addCharacterFromUri(uri, name)) {
                is CharacterRepository.AddResult.Added -> {
                    message.value = "캐릭터를 등록했어요."
                    assignToEmptySlot(result.id)
                }

                is CharacterRepository.AddResult.Failed -> {
                    message.value = describe(result.reason)
                }
            }
        }
    }

    private suspend fun assignToEmptySlot(characterId: Long) {
        val pair = repository.getActivePair()
        when {
            pair == null -> repository.setActivePair(characterId, null)
            pair.characterBId == null && pair.characterAId != characterId ->
                repository.setActivePair(pair.characterAId, characterId)

            else -> Unit
        }
    }

    fun updateCharacter(character: CharacterEntity) {
        viewModelScope.launch { repository.updateCharacter(character) }
    }

    fun deleteCharacter(character: CharacterEntity) {
        viewModelScope.launch {
            repository.deleteCharacter(character)
            message.value = "${character.name} 을(를) 지웠어요."
        }
    }

    /**
     * 두 캐릭터의 키를 같은 값으로 맞춘다.
     * 이미지 여백은 등록할 때 잘라내므로, 높이를 같게 하면 실제로 같은 키로 보인다.
     */
    fun matchHeights(targetHeightDp: Int) {
        val state = _uiState.value
        val height = targetHeightDp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        viewModelScope.launch {
            listOfNotNull(state.characterA, state.characterB)
                .filter { it.displayHeightDp != height }
                .forEach { repository.updateCharacter(it.copy(displayHeightDp = height)) }
            message.value = "두 캐릭터의 키를 ${height}dp 로 맞췄어요."
        }
    }

    fun setPair(aId: Long, bId: Long?) {
        viewModelScope.launch { repository.setActivePair(aId, bId) }
    }

    fun updatePair(pair: PairEntity) {
        viewModelScope.launch { repository.updatePair(pair) }
    }

    // ------------------------------------------------------------------ 설정

    fun setMode(mode: OverlayMode) = launchSetting { settingsStore.setMode(mode) }
    fun setScale(percent: Int) = launchSetting { settingsStore.setScalePercent(percent) }
    fun setOpacity(percent: Int) = launchSetting { settingsStore.setOpacityPercent(percent) }
    fun setLinkedDrag(linked: Boolean) = launchSetting { settingsStore.setLinkedDrag(linked) }
    fun setMusicReaction(on: Boolean) = launchSetting { settingsStore.setMusicReaction(on) }
    fun setEffectsEnabled(on: Boolean) = launchSetting { settingsStore.setEffectsEnabled(on) }
    fun setOnboardingCompleted() = launchSetting { settingsStore.setOnboardingCompleted(true) }

    fun showAgain() = launchSetting { settingsStore.setHiddenUntil(0L) }

    fun hideForMinutes(minutes: Int) = launchSetting {
        settingsStore.setHiddenUntil(System.currentTimeMillis() + minutes * 60_000L)
    }

    /** 위치를 처음 상태로 되돌린다. 캐릭터를 화면 밖으로 밀어 버렸을 때 쓴다. */
    fun resetPositions() = launchSetting {
        settingsStore.setPositionA(OverlaySettings.UNSET_POSITION, OverlaySettings.UNSET_POSITION)
        settingsStore.setPositionB(OverlaySettings.UNSET_POSITION, OverlaySettings.UNSET_POSITION)
        message.value = "캐릭터 위치를 처음으로 되돌렸어요. 오버레이를 껐다 켜면 적용됩니다."
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ------------------------------------------------------------------ 오버레이

    fun startOverlay() {
        val context = getApplication<Application>()
        if (!Settings.canDrawOverlays(context)) {
            message.value = "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요."
            return
        }
        if (_uiState.value.characters.isEmpty()) {
            message.value = "표시할 캐릭터가 없어요."
            return
        }
        OverlayService.start(context)
    }

    fun stopOverlay() {
        OverlayService.stop(getApplication())
    }

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        private const val MIN_HEIGHT_DP = 60
        private const val MAX_HEIGHT_DP = 320
    }

    private fun describe(reason: ImageImporter.Reason): String = when (reason) {
        ImageImporter.Reason.UNREADABLE ->
            "이미지를 읽을 수 없어요. 다른 파일로 시도해 주세요."

        ImageImporter.Reason.FULLY_TRANSPARENT ->
            "이미지가 전부 투명해요. 캐릭터가 그려진 파일을 골라 주세요."

        ImageImporter.Reason.WRITE_FAILED ->
            "이미지를 저장하지 못했어요. 저장 공간을 확인해 주세요."

        ImageImporter.Reason.OUT_OF_MEMORY ->
            "이미지가 너무 커서 처리하지 못했어요. 더 작은 이미지를 써 주세요."
    }
}
