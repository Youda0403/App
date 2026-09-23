package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairplay.app.data.OverlayMode
import com.pairplay.app.data.OverlaySettings
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySettingsScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val launchers = rememberPermissionLaunchers { viewModel.refreshPermissions() }
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오버레이 설정") },
                navigationIcon = { IconButton(onClick = onBack) { Text("뒤로") } }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SectionCard("표시 방식") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == OverlayMode.SINGLE,
                        onClick = { viewModel.setMode(OverlayMode.SINGLE) },
                        label = { Text("한 명") }
                    )
                    FilterChip(
                        selected = settings.mode == OverlayMode.PAIR,
                        onClick = { viewModel.setMode(OverlayMode.PAIR) },
                        label = { Text("두 명") }
                    )
                }
                LabeledSwitch(
                    label = "함께 끌기",
                    description = "한 명을 끌면 나머지 한 명도 같이 움직여요.",
                    checked = settings.linkedDrag
                ) { viewModel.setLinkedDrag(it) }
            }

            SectionCard("크기와 투명도") {
                LabeledSlider(
                    label = "크기",
                    value = settings.scalePercent,
                    range = OverlaySettings.MIN_SCALE_PERCENT..OverlaySettings.MAX_SCALE_PERCENT,
                    suffix = "%"
                ) { viewModel.setScale(it) }
                LabeledSlider(
                    label = "불투명도",
                    value = settings.opacityPercent,
                    range = OverlaySettings.MIN_OPACITY_PERCENT..OverlaySettings.MAX_OPACITY_PERCENT,
                    suffix = "%"
                ) { viewModel.setOpacity(it) }
                Text(
                    "설정은 오버레이를 껐다 켜지 않아도 바로 반영돼요.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("숨기기") {
                Text(
                    "일반 오버레이 앱은 지금 화면이 비밀번호 입력 화면인지 알 수 없어요. " +
                        "그래서 자동으로 피해 주지는 못하고, 대신 즉시 치울 수단을 둡니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("• 캐릭터를 길게 누르면 바로 숨겨져요.")
                Text("• 알림창의 '숨기기' 버튼으로도 됩니다.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.hideForMinutes(5) }) { Text("5분 숨기기") }
                    OutlinedButton(onClick = { viewModel.hideForMinutes(30) }) { Text("30분") }
                }
                if (settings.isHiddenAt(System.currentTimeMillis())) {
                    OutlinedButton(onClick = { viewModel.showAgain() }) { Text("다시 보이기") }
                }
            }

            SectionCard("활발함") {
                LabeledSlider(
                    label = "정도",
                    value = settings.activityPercent,
                    range = 0..100,
                    suffix = "%"
                ) { viewModel.setActivity(it) }
                Text(
                    "높일수록 더 멀리 돌아다니고, 동작이 빨라지고, 상황극도 자주 나와요. " +
                        "낮추면 조용히 있습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("기분 표시") {
                LabeledSwitch(
                    label = "기분 기호",
                    description = "놀라면 느낌표, 머쓱하면 땀처럼 지금 기분을 작은 기호로 알려 줘요. " +
                        "둘이 주고받은 걸 알아보기 쉬워집니다.",
                    checked = settings.bubblesEnabled
                ) { viewModel.setBubblesEnabled(it) }
                LabeledSwitch(
                    label = "하트와 음표",
                    description = "톡 치거나 쓰다듬을 때 하트, 음악이 시작될 때 음표가 올라와요.",
                    checked = settings.effectsEnabled
                ) { viewModel.setEffectsEnabled(it) }
            }

            SectionCard("음악 반응") {
                LabeledSwitch(
                    label = "음악에 맞춰 움직이기",
                    description = "재생/일시정지에 반응해요. 박자 분석은 하지 않습니다.",
                    checked = settings.musicReactionEnabled,
                    enabled = state.notificationAccessGranted
                ) { viewModel.setMusicReaction(it) }

                if (!state.notificationAccessGranted) {
                    Text(
                        "알림 접근 권한이 꺼져 있어 음악 반응을 쓸 수 없어요. " +
                            "안드로이드가 재생 상태를 이 경로로만 알려줍니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { launchers.requestNotificationAccess() }) {
                        Text("알림 접근 설정 열기")
                    }
                }
            }

            SectionCard("위치") {
                Text(
                    "캐릭터를 화면 구석으로 밀어 버렸다면 되돌릴 수 있어요.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { viewModel.resetPositions() }) {
                    Text("위치 처음으로 되돌리기")
                }
            }

            SectionCard("배터리와 제약") {
                Text(
                    "• 캐릭터가 떠 있는 동안은 상시 알림이 함께 표시돼요. 안드로이드 규칙입니다.\n" +
                        "• 일부 앱(은행 앱 등)은 보안을 위해 화면 위 오버레이를 강제로 숨깁니다. " +
                        "그럴 때 캐릭터가 잠깐 사라지는 것은 정상이에요.\n" +
                        "• 키보드나 시스템 설정 화면 위에서는 표시가 제한될 수 있어요.\n" +
                        "• 휴대폰을 다시 켠 뒤에는 앱을 열어 직접 시작해야 해요. " +
                        "안드로이드 15부터 이런 종류의 서비스는 부팅 직후 자동 시작이 제한됩니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "관계 설정, 상황극, 장면 편집기, 홈 위젯은 다음 버전에서 추가됩니다.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}
