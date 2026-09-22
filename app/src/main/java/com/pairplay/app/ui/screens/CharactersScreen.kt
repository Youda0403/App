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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.addCharacter(uri, "새 자캐")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캐릭터 관리") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("뒤로") }
                }
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
                onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("이미지에서 캐릭터 추가") }

            Text(
                "표시할 두 명을 고르세요. 한 명만 골라도 됩니다.",
                style = MaterialTheme.typography.bodySmall
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.characters, key = { it.id }) { character ->
                    CharacterRow(
                        character = character,
                        isA = state.characterA?.id == character.id,
                        isB = state.characterB?.id == character.id,
                        onSetA = {
                            val bId = state.characterB?.id?.takeIf { it != character.id }
                            viewModel.setPair(character.id, bId)
                        },
                        onSetB = {
                            val aId = state.characterA?.id
                            if (aId != null && aId != character.id) {
                                viewModel.setPair(aId, character.id)
                            }
                        },
                        onEdit = { onEdit(character.id) },
                        onDelete = { pendingDelete = character }
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("캐릭터를 지울까요?") },
            text = { Text("${toDelete.name} 과(와) 등록한 이미지가 함께 지워집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCharacter(toDelete)
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
private fun CharacterRow(
    character: CharacterEntity,
    isA: Boolean,
    isB: Boolean,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(title = character.name) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CharacterThumbnail(character, sizeDp = 64)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        isA -> "지금 첫 번째 자리"
                        isB -> "지금 두 번째 자리"
                        else -> "표시 안 함"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (character.isBuiltIn) {
                    Text("기본 제공 캐릭터", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSetA, enabled = !isA) { Text("첫째로") }
            OutlinedButton(onClick = onSetB, enabled = !isB) { Text("둘째로") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("수정") }
            OutlinedButton(onClick = onDelete) { Text("삭제") }
        }
    }
}
