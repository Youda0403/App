package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    characterId: Long,
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val character = state.characters.firstOrNull { it.id == characterId }

    var draft by remember(characterId) { mutableStateOf<CharacterEntity?>(null) }
    LaunchedEffect(character?.id) {
        if (draft == null && character != null) draft = character
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캐릭터 수정") },
                navigationIcon = { IconButton(onClick = onBack) { Text("뒤로") } }
            )
        }
    ) { inner ->
        val editing = draft
        if (editing == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("캐릭터를 찾을 수 없어요.")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SectionCard("미리보기") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CharacterThumbnail(editing, sizeDp = 140)
                }
                Text(
                    "가장자리 투명 여백은 등록할 때 이미 잘라냈어요. " +
                        "여백이 작을수록 다른 앱을 쓸 때 방해가 덜 됩니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("이름") {
                OutlinedTextField(
                    value = editing.name,
                    onValueChange = { draft = editing.copy(name = it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard("표시 크기") {
                LabeledSlider(
                    label = "높이",
                    value = editing.displayHeightDp,
                    range = 60..320,
                    suffix = " dp"
                ) { draft = editing.copy(displayHeightDp = it) }
                Text(
                    "가로는 원본 비율에 맞춰 자동으로 정해집니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("바닥 기준점") {
                Text(
                    "캐릭터가 '서 있는 위치'를 이미지 어디로 볼지 정해요. " +
                        "발밑이 아래쪽 가운데가 아니면 조절하세요.",
                    style = MaterialTheme.typography.bodySmall
                )
                LabeledSlider(
                    label = "가로",
                    value = (editing.anchorXRatio * 100).roundToInt(),
                    range = 0..100,
                    suffix = "%"
                ) { draft = editing.copy(anchorXRatio = it / 100f) }
                LabeledSlider(
                    label = "세로",
                    value = (editing.anchorYRatio * 100).roundToInt(),
                    range = 0..100,
                    suffix = "%"
                ) { draft = editing.copy(anchorYRatio = it / 100f) }
            }

            SectionCard("방향") {
                LabeledSwitch(
                    label = "좌우 반전",
                    description = "원본 이미지가 반대쪽을 보고 있을 때 켜세요.",
                    checked = editing.flipHorizontal
                ) { draft = editing.copy(flipHorizontal = it) }
            }

            SectionCard("성격") {
                Text(
                    "지금은 움직임 빈도에만 쓰여요. 다음 버전의 관계 엔진이 이 값을 함께 씁니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                LabeledSlider("활동성", editing.traitEnergy, 0..100) {
                    draft = editing.copy(traitEnergy = it)
                }
                LabeledSlider("장난기", editing.traitMischief, 0..100) {
                    draft = editing.copy(traitMischief = it)
                }
                LabeledSlider("다정함", editing.traitWarmth, 0..100) {
                    draft = editing.copy(traitWarmth = it)
                }
                LabeledSlider("적극성", editing.traitAssertiveness, 0..100) {
                    draft = editing.copy(traitAssertiveness = it)
                }
                LabeledSlider("수줍음", editing.traitShyness, 0..100) {
                    draft = editing.copy(traitShyness = it)
                }
            }

            Button(
                onClick = {
                    viewModel.updateCharacter(editing)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("저장") }
        }
    }
}
