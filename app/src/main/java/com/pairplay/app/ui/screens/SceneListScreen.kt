package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairplay.app.data.SceneEntity
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.SceneCodec
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 내가 만든 장면 목록.
 * 추가·수정·복제·삭제·켜고 끄기를 여기서 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneListScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SceneEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("장면 편집") },
                navigationIcon = { IconButton(onClick = onBack) { Text("뒤로") } }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.createScene { id -> onEdit(id) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("새 장면 만들기") }

            Text(
                "'언제 → 누가 무엇을 → 그다음' 순서로 장면을 짤 수 있어요. " +
                    "내가 만든 장면은 기본 장면보다 먼저 나옵니다.",
                style = MaterialTheme.typography.bodySmall
            )

            if (state.scenes.isEmpty()) {
                SectionCard("아직 만든 장면이 없어요") {
                    Text(
                        "지금은 관계에 맞는 기본 장면만 나와요. " +
                            "위 버튼으로 나만의 장면을 만들 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.scenes, key = { it.id }) { scene ->
                    SceneRow(
                        scene = scene,
                        overlayRunning = state.overlayRunning,
                        onToggle = { viewModel.updateScene(scene.copy(enabled = it)) },
                        onEdit = { onEdit(scene.id) },
                        onDuplicate = { viewModel.duplicateScene(scene) },
                        onRun = { viewModel.runSceneNow(scene) },
                        onDelete = { pendingDelete = scene }
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("장면을 지울까요?") },
            text = { Text("${toDelete.name} 이(가) 사라집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteScene(toDelete)
                    pendingDelete = null
                }) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun SceneRow(
    scene: SceneEntity,
    overlayRunning: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val steps = remember(scene.orderedActions) {
        SceneCodec.decodeSteps(scene.orderedActions)
    }

    SectionCard(title = scene.name) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    triggerLabel(SceneTrigger.fromName(scene.trigger)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (steps.isEmpty()) {
                        "행동이 없어요 — 지금은 실행되지 않습니다"
                    } else {
                        "${steps.size}단계 · ${scene.cooldownMs / 1000}초마다 한 번"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = scene.enabled, onCheckedChange = onToggle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("수정") }
            OutlinedButton(onClick = onRun, enabled = overlayRunning && steps.isNotEmpty()) {
                Text("실행해 보기")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDuplicate) { Text("복제") }
            OutlinedButton(onClick = onDelete) { Text("삭제") }
        }
        if (!overlayRunning) {
            Text(
                "오버레이를 시작하면 '실행해 보기' 로 바로 확인할 수 있어요.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
