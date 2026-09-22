package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onOpenCharacters: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSizeMatch: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PAIRPLAY") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SectionCard("지금 떠 있는 페어") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CharacterThumbnail(state.characterA, sizeDp = 96)
                        Text(state.characterA?.name ?: "없음")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CharacterThumbnail(state.characterB, sizeDp = 96)
                        Text(state.characterB?.name ?: "없음")
                    }
                }
                if (state.characterB == null) {
                    Text(
                        "한 명만 등록해도 정상 동작해요. 두 명이면 서로를 바라보고 다가갑니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionCard("오버레이") {
                if (!state.canDrawOverlays) {
                    Text(
                        "'다른 앱 위에 표시' 권한이 꺼져 있어요. 설정에서 허용해 주세요.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startOverlay() },
                        enabled = !state.overlayRunning
                    ) { Text("시작") }
                    OutlinedButton(
                        onClick = { viewModel.stopOverlay() },
                        enabled = state.overlayRunning
                    ) { Text("중지") }
                }
                Text(
                    if (state.overlayRunning) "실행 중" else "멈춰 있음",
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.settings.isHiddenAt(System.currentTimeMillis())) {
                    Text("지금은 숨김 상태예요.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { viewModel.showAgain() }) { Text("다시 보이기") }
                }
            }

            SectionCard("관리") {
                Button(onClick = onOpenCharacters, modifier = Modifier.fillMaxWidth()) {
                    Text("캐릭터 관리")
                }
                Button(onClick = onOpenSizeMatch, modifier = Modifier.fillMaxWidth()) {
                    Text("두 캐릭터 키 맞추기")
                }
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("오버레이 설정")
                }
            }

            SectionCard("사용 요령") {
                Text("• 캐릭터를 끌어서 옮길 수 있어요.")
                Text("• 캐릭터를 톡 치면 하트가 뜨고, 짝이 있으면 쳐다봐요.")
                Text("• 캐릭터 위를 좌우로 문지르면 쓰다듬기가 돼요. 하트가 계속 올라와요.")
                Text("• 걷다가 화면 끝에 닿으면 부딪히고 돌아서요.")
                Text("• 캐릭터를 길게 누르면 바로 숨겨져요. 비밀번호 입력처럼 가려지면 곤란할 때 쓰세요.")
                Text("• 알림창에서도 숨기기와 중지를 할 수 있어요.")
            }

            SectionCard("아직 준비 중") {
                Text(
                    "관계 엔진, 상황극 스케줄러, 장면 편집기, 홈 화면 위젯은 다음 버전에서 추가됩니다. " +
                        "지금 저장하는 캐릭터와 설정은 그대로 이어져요.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
