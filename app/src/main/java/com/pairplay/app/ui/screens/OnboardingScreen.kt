package com.pairplay.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 첫 실행 안내. 권한을 거절해도 다음으로 넘어갈 수 있게 한다.
 * 문서 요구사항: 권한을 거절해도 가능한 기능은 유지한다.
 */
@Composable
fun OnboardingScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onFinished: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val launchers = rememberPermissionLaunchers { viewModel.refreshPermissions() }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.addCharacter(uri, "내 자캐")
    }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("PAIRPLAY", style = MaterialTheme.typography.headlineMedium)

            when (step) {
                0 -> IntroStep()
                1 -> CharacterStep(
                    characterCount = state.characters.count { !it.isBuiltIn },
                    onPick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )

                2 -> OverlayPermissionStep(
                    granted = state.canDrawOverlays,
                    onRequest = { launchers.requestOverlay() }
                )

                else -> MusicPermissionStep(
                    granted = state.notificationAccessGranted,
                    onRequestMusic = { launchers.requestNotificationAccess() },
                    onRequestNotification = { launchers.requestPostNotifications() },
                    needsPostNotifications = launchers.needsPostNotifications
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) { Text("이전") }
                }
                Button(
                    onClick = { if (step >= 3) onFinished() else step++ },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (step >= 3) "시작하기" else "다음")
                }
            }

            if (step in 1..2) {
                TextButton(onClick = { step++ }) { Text("나중에 하기") }
            }
        }
    }
}

@Composable
private fun IntroStep() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("자캐가 다른 앱 위에 떠서 생활해요.", style = MaterialTheme.typography.titleMedium)
        Text(
            "이미지를 등록하면 두 캐릭터가 화면 위를 돌아다니고, 터치하거나 음악을 틀면 반응합니다.\n\n" +
                "계정이나 서버 없이 이 휴대폰 안에서만 동작해요."
        )
    }
}

@Composable
private fun CharacterStep(characterCount: Int, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("자캐 이미지를 등록해요", style = MaterialTheme.typography.titleMedium)
        Text(
            "배경이 투명한 PNG 를 추천해요. 가장자리의 투명한 여백은 자동으로 잘라내서 " +
                "다른 앱을 쓸 때 방해가 되지 않게 합니다."
        )
        Button(onClick = onPick) { Text("이미지 고르기") }
        Text(
            if (characterCount > 0) {
                "등록한 캐릭터: ${characterCount}명"
            } else {
                "지금 등록하지 않아도 기본 캐릭터로 먼저 둘러볼 수 있어요."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun OverlayPermissionStep(granted: Boolean, onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("'다른 앱 위에 표시' 권한", style = MaterialTheme.typography.titleMedium)
        Text(
            "이 권한이 있어야 캐릭터를 다른 앱 위에 띄울 수 있어요. " +
                "캐릭터 창은 이미지 크기만큼만 떠 있어서, 창 바깥은 원래 앱을 그대로 쓸 수 있습니다."
        )
        if (granted) {
            Text("허용됨", style = MaterialTheme.typography.bodyLarge)
        } else {
            Button(onClick = onRequest) { Text("권한 설정 열기") }
        }
    }
}

@Composable
private fun MusicPermissionStep(
    granted: Boolean,
    needsPostNotifications: Boolean,
    onRequestMusic: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("선택 권한", style = MaterialTheme.typography.titleMedium)
        Text(
            "음악 반응을 쓰려면 '알림 접근'을 켜야 해요. 안드로이드가 음악 재생 상태를 " +
                "이 경로로만 알려주기 때문입니다. 알림 내용은 읽지 않습니다.\n\n" +
                "거절해도 오버레이는 그대로 동작하고, 음악 반응만 꺼진 상태가 됩니다."
        )
        if (granted) {
            Text("알림 접근 허용됨", style = MaterialTheme.typography.bodyLarge)
        } else {
            Button(onClick = onRequestMusic) { Text("알림 접근 설정 열기") }
        }

        if (needsPostNotifications) {
            Text(
                "상시 알림에서 캐릭터를 숨기거나 중지하려면 알림 표시 권한도 필요해요.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onRequestNotification) { Text("알림 표시 권한 요청") }
        }
    }
}
