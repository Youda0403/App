package com.pairplay.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.CharacterRepository
import com.pairplay.app.data.OverlayMode
import com.pairplay.app.data.OverlaySettings
import com.pairplay.app.data.OverlaySettingsStore
import com.pairplay.app.data.PairEntity
import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import com.pairplay.app.device.ForegroundAppWatcher
import com.pairplay.app.engine.AppCategory
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Expression
import com.pairplay.app.image.ImageImporter
import com.pairplay.app.music.MusicWatcher
import com.pairplay.app.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PairPlayUiState(
    val characters: List<CharacterEntity> = emptyList(),
    val activePair: PairEntity? = null,
    val settings: OverlaySettings = OverlaySettings(),
    val overlayRunning: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    /** '사용 정보 접근' 권한. 지금 쓰는 앱에 반응하려면 필요하다. */
    val usageAccessGranted: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
    /** 지금 도는 상황극 이름. 상황극이 실제로 돌고 있는지 확인할 수 있다. */
    val currentScene: String? = null
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
        val permissions: Permissions,
        val message: String?,
        val currentScene: String?
    )

    /** 설정 화면에서 사용자가 직접 켜야 하는 권한들. */
    private data class Permissions(
        val canDrawOverlays: Boolean,
        val notificationAccess: Boolean,
        val usageAccess: Boolean
    )

    /** 휴대폰에 깔린 앱 하나. 숨을 앱을 정하는 화면에서 쓴다. */
    data class InstalledApp(
        val packageName: String,
        val label: String,
        val icon: Bitmap?
    )

    private val _installedApps = MutableStateFlow<List<InstalledApp>?>(null)

    /** 아직 불러오지 않았으면 null. 앱이 많으면 불러오는 데 잠깐 걸린다. */
    val installedApps: StateFlow<List<InstalledApp>?> = _installedApps.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureDefaultCharacters()
        }
        viewModelScope.launch {
            val ambient = combine(
                permissionState,
                message,
                OverlayService.currentScene
            ) { permissions, text, scene ->
                Ambient(permissions, text, scene)
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
                    canDrawOverlays = outside.permissions.canDrawOverlays,
                    notificationAccessGranted = outside.permissions.notificationAccess,
                    usageAccessGranted = outside.permissions.usageAccess,
                    loading = false,
                    message = outside.message,
                    currentScene = outside.currentScene
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

    private fun readPermissionState(): Permissions {
        val context = getApplication<Application>()
        return Permissions(
            canDrawOverlays = Settings.canDrawOverlays(context),
            notificationAccess = MusicWatcher.isNotificationAccessGranted(context),
            usageAccess = ForegroundAppWatcher.hasPermission(context)
        )
    }

    // ------------------------------------------------------------------ 숨을 앱

    /**
     * 홈 화면에 아이콘이 있는 앱들을 불러온다. 한 번 불러오면 다시 부르지 않는다.
     * 아이콘을 만드는 데 시간이 조금 걸려 화면 밖에서 한다.
     */
    fun loadInstalledApps(force: Boolean = false) {
        if (_installedApps.value != null && !force) return
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) { readInstalledApps() }
        }
    }

    private fun readInstalledApps(): List<InstalledApp> {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val iconPx = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            // 이 앱 자신은 뺀다. 여기서는 반응하지 않는다.
            .filter { it.packageName != context.packageName }
            .map { info ->
                val icon = try {
                    pm.getApplicationIcon(info).toBitmap(iconPx, iconPx)
                } catch (e: Exception) {
                    null
                }
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = icon
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun setAppAwareness(on: Boolean) = launchSetting { settingsStore.setAppAwareness(on) }

    /** 한 앱에서 숨을지 직접 정한다. null 이면 기본값으로 되돌린다. */
    fun setAppRule(packageName: String, category: AppCategory?) =
        launchSetting { settingsStore.setAppRule(packageName, category) }

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

    /** 기본 그림만 다른 이미지로 갈아 끼운다. 다른 설정은 그대로 둔다. */
    fun replaceBaseImage(characterId: Long, uri: Uri) {
        viewModelScope.launch {
            when (val result = repository.replaceBaseImage(characterId, uri)) {
                is CharacterRepository.AddResult.Added ->
                    message.value = "기본 그림을 바꿨어요."

                is CharacterRepository.AddResult.Failed ->
                    message.value = describe(result.reason)
            }
        }
    }

    /** 표정 하나에 쓸 그림을 등록한다. */
    fun setExpressionImage(characterId: Long, expression: Expression, uri: Uri) {
        viewModelScope.launch {
            when (val result = repository.setExpressionImage(characterId, expression, uri)) {
                is CharacterRepository.AddResult.Added ->
                    message.value = "${expression.label} 을(를) 등록했어요."

                is CharacterRepository.AddResult.Failed ->
                    message.value = describe(result.reason)
            }
        }
    }

    /** 등록해 둔 표정 그림을 지운다. */
    fun clearExpressionImage(characterId: Long, expression: Expression) {
        viewModelScope.launch {
            repository.clearExpressionImage(characterId, expression)
            message.value = "${expression.label} 을(를) 기본 그림으로 되돌렸어요."
        }
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

    /**
     * 관계를 바꾼다. 관계는 사용자만 바꿀 수 있고 앱이 스스로 바꾸지 않는다.
     * 아직 짝이 만들어지지 않았다면 지금 캐릭터들로 만들어 준다.
     */
    fun setRelationship(
        type: RelationshipType,
        direction: RelationshipDirection,
        customLabel: String?
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            var pair = repository.getActivePair()
            if (pair == null) {
                val a = state.characterA ?: return@launch
                repository.setActivePair(a.id, state.characterB?.id)
                pair = repository.getActivePair() ?: return@launch
            }
            repository.updatePair(
                pair.copy(
                    relationship = type.name,
                    direction = if (type.needsDirection) {
                        direction.name
                    } else {
                        RelationshipDirection.MUTUAL.name
                    },
                    customRelationshipLabel = customLabel?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /** 이 캐릭터가 하지 않을 동작을 정한다. 장면에 그 동작이 있으면 장면째로 빠진다. */
    fun setBlockedActions(character: CharacterEntity, blocked: Set<CharacterAction>) {
        viewModelScope.launch {
            repository.updateCharacter(
                character.copy(blockedActions = blocked.joinToString(",") { it.id })
            )
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
    fun setBubblesEnabled(on: Boolean) = launchSetting { settingsStore.setBubblesEnabled(on) }

    fun setDeviceReactions(on: Boolean) = launchSetting { settingsStore.setDeviceReactions(on) }
    fun setActivity(percent: Int) = launchSetting { settingsStore.setActivityPercent(percent) }
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

        /** 앱별 설정 목록에 쓰는 아이콘 크기. */
        private const val ICON_DP = 40
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
