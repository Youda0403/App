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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.engine.Expression
import com.pairplay.app.engine.ExpressionSlots
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel
import java.io.File
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

    val pickBaseImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.replaceBaseImage(characterId, uri)
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

            SectionCard("기본 그림") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 초안이 아니라 저장된 값을 보여 준다. 그림은 저장 버튼을
                    // 기다리지 않고 바로 바뀌기 때문이다.
                    CharacterThumbnail(character ?: editing, sizeDp = 140)
                }
                OutlinedButton(
                    onClick = {
                        pickBaseImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("다른 그림으로 바꾸기") }
                Text(
                    "이름·크기·기준점·성격·표정은 그대로 두고 그림만 바꿔요. " +
                        "가장자리 투명 여백은 등록할 때 자동으로 잘라냅니다. " +
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

            FacingSection(character ?: editing, editing) { draft = it }

            ExpressionSection(characterId, character ?: editing, viewModel)

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
                    // 그림(기본·표정)은 저장 버튼을 기다리지 않고 바로 반영된다.
                    // 그래서 여기서 초안을 그대로 저장하면 그 사이 바꾼 그림이
                    // 옛날 값으로 덮여 사라진다. 그림 쪽만 최신 값을 가져와 붙인다.
                    val latest = character ?: editing
                    viewModel.updateCharacter(
                        editing.copy(
                            imagePath = latest.imagePath,
                            originalImagePath = latest.originalImagePath,
                            animationSlots = latest.animationSlots,
                            isBuiltIn = latest.isBuiltIn
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("저장") }
        }
    }
}

/**
 * 표정별 그림 등록.
 *
 * 이미지 한 장으로는 표정을 바꿀 수 없어서, 웃는 얼굴·화난 얼굴 같은 그림을
 * 따로 넣어 두면 기분에 맞춰 갈아 끼운다. 넣지 않은 표정은 기본 그림 그대로다.
 *
 * 저장 버튼을 기다리지 않고 바로 반영한다. 파일을 들여오는 일이라
 * 이름·크기 같은 값과 함께 되돌리기 어렵기 때문이다.
 */
@Composable
private fun ExpressionSection(
    characterId: Long,
    character: CharacterEntity,
    viewModel: PairPlayViewModel
) {
    var target by remember { mutableStateOf(Expression.HAPPY) }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.setExpressionImage(characterId, target, uri)
    }

    val slots = ExpressionSlots.parse(character.animationSlots)

    SectionCard("표정") {
        Text(
            "기분에 따라 그림을 바꿔 끼워요. 넣지 않은 표정은 기본 그림 그대로 나옵니다. " +
                "하나도 안 넣어도 괜찮아요.",
            style = MaterialTheme.typography.bodySmall
        )

        Expression.registerable.forEachIndexed { index, expression ->
            if (index > 0) HorizontalDivider()
            val path = slots[expression]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExpressionThumbnail(path)
                Column(modifier = Modifier.weight(1f)) {
                    Text(expression.label, style = MaterialTheme.typography.bodyMedium)
                    Text(expression.hint, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = {
                        target = expression
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) { Text(if (path == null) "고르기" else "바꾸기") }
                    if (path != null) {
                        TextButton(onClick = {
                            viewModel.clearExpressionImage(characterId, expression)
                        }) { Text("지우기") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressionThumbnail(path: String?) {
    val file = path?.let { File(it) }
    if (file == null || !file.exists()) {
        Text(
            "없음",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.size(44.dp).padding(top = 14.dp)
        )
    } else {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(44.dp)
        )
    }
}

/**
 * 바라보는 방향.
 *
 * 앱은 '오른쪽을 볼 때'를 기준으로 그림을 그리고, 왼쪽을 볼 때는 좌우를 뒤집는다.
 * 그런데 원본 그림이 이미 왼쪽을 보고 있으면 기준이 반대가 되어, 둘이 마주 보라고
 * 세워 놔도 서로 등을 돌린 것처럼 보인다.
 *
 * 예전에는 '좌우 반전' 스위치 하나였는데, 켜면 어떻게 되는지 볼 수가 없어서
 * 맞게 맞춘 건지 알기 어려웠다. 지금은 두 방향을 나란히 보여 주고 고르게 한다.
 */
@Composable
private fun FacingSection(
    saved: CharacterEntity,
    editing: CharacterEntity,
    onChange: (CharacterEntity) -> Unit
) {
    SectionCard("바라보는 방향") {
        Text(
            "원본 그림이 어느 쪽을 보고 있나요? 아래 두 그림 중 " +
                "얼굴이 제대로 보이는 쪽을 고르세요. 둘이 서로 마주 보게 하는 데 쓰입니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FacingChoice(
                label = "오른쪽을 봐요",
                character = saved,
                // 오른쪽을 볼 때 그대로 그려지려면 원본이 오른쪽을 보고 있어야 한다.
                mirrored = false,
                selected = !editing.flipHorizontal,
                modifier = Modifier.weight(1f)
            ) { onChange(editing.copy(flipHorizontal = false)) }

            FacingChoice(
                label = "왼쪽을 봐요",
                character = saved,
                mirrored = true,
                selected = editing.flipHorizontal,
                modifier = Modifier.weight(1f)
            ) { onChange(editing.copy(flipHorizontal = true)) }
        }
        Text(
            "고른 쪽이 '원본 그대로'가 되고, 반대쪽을 볼 때는 앱이 좌우를 뒤집어 그려요.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FacingChoice(
    label: String,
    character: CharacterEntity,
    mirrored: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val file = File(character.imagePath).takeIf { it.exists() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (file == null) {
            Text("이미지 없음", style = MaterialTheme.typography.bodySmall)
        } else {
            AsyncImage(
                model = file,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
            )
        }
        if (selected) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}
