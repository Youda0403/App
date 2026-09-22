package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.pairplay.app.data.SceneEntity
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Performer
import com.pairplay.app.engine.SceneCodec
import com.pairplay.app.engine.ScriptStep
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 장면 한 편을 짜는 화면.
 *
 * '언제 → 누가 무엇을 → 그다음' 순서로 마디를 쌓는다.
 * 앞 마디가 끝나야 다음 마디가 시작되므로, 목록 순서가 그대로 재생 순서다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SceneEditScreen(
    sceneId: Long,
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val stored = state.scenes.firstOrNull { it.id == sceneId }

    var draft by remember(sceneId) { mutableStateOf<SceneEntity?>(null) }
    var steps by remember(sceneId) { mutableStateOf<List<ScriptStep>>(emptyList()) }

    LaunchedEffect(stored?.id) {
        if (draft == null && stored != null) {
            draft = stored
            steps = SceneCodec.decodeSteps(stored.orderedActions)
        }
    }

    var pickingFor by remember { mutableStateOf<Int?>(null) }

    val nameA = state.characterA?.name ?: "첫째"
    val nameB = state.characterB?.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("장면 짜기") },
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
            ) { Text("장면을 찾을 수 없어요.") }
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

            SectionCard("이름") {
                OutlinedTextField(
                    value = editing.name,
                    onValueChange = { draft = editing.copy(name = it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard("언제 나올까요") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EDITABLE_TRIGGERS.forEach { trigger ->
                        FilterChip(
                            selected = SceneTrigger.fromName(editing.trigger) == trigger,
                            onClick = { draft = editing.copy(trigger = trigger.name) },
                            label = { Text(triggerLabel(trigger)) }
                        )
                    }
                }
            }

            SectionCard("순서") {
                Text(
                    "위에서 아래로 차례대로 진행돼요. 앞 행동이 끝나야 다음이 시작됩니다.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (steps.isEmpty()) {
                    Text("행동이 없어요. 아래에서 하나 추가해 주세요.")
                }

                steps.forEachIndexed { index, step ->
                    StepEditor(
                        index = index,
                        step = step,
                        nameA = nameA,
                        nameB = nameB,
                        isFirst = index == 0,
                        isLast = index == steps.lastIndex,
                        onPerformerChange = { performer ->
                            steps = steps.replaceAt(index, step.copy(performer = performer))
                        },
                        onPickAction = { pickingFor = index },
                        onDurationChange = { duration ->
                            steps = steps.replaceAt(index, step.copy(durationMs = duration))
                        },
                        onMoveUp = { steps = steps.swap(index, index - 1) },
                        onMoveDown = { steps = steps.swap(index, index + 1) },
                        onRemove = { steps = steps.filterIndexed { i, _ -> i != index } }
                    )
                }

                OutlinedButton(
                    onClick = {
                        steps = steps + ScriptStep(Performer.A, CharacterAction.LOOK_AT)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("행동 추가") }
            }

            SectionCard("다시 나오기까지") {
                LabeledSlider(
                    label = "기다리는 시간",
                    // 슬라이더 범위를 벗어난 값이 들어오면 표시가 어긋난다.
                    value = (editing.cooldownMs / 1000L).toInt().coerceIn(5, 180),
                    range = 5..180,
                    suffix = "초"
                ) { draft = editing.copy(cooldownMs = it * 1000L) }
                Text(
                    "같은 장면이 연달아 나오지 않게 하는 간격이에요.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (nameB == null && steps.any { it.performer != Performer.A }) {
                SectionCard("알려 드려요") {
                    Text(
                        "지금은 캐릭터가 한 명뿐이라 둘째나 '함께' 가 들어간 장면은 나오지 않아요. " +
                            "캐릭터 관리에서 두 번째 캐릭터를 고르면 동작합니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.updateScene(
                        editing.copy(orderedActions = SceneCodec.encodeSteps(steps))
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("저장") }

            OutlinedButton(
                onClick = {
                    val saved = editing.copy(orderedActions = SceneCodec.encodeSteps(steps))
                    viewModel.updateScene(saved)
                    viewModel.runSceneNow(saved)
                },
                enabled = state.overlayRunning && steps.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) { Text("저장하고 화면에서 실행해 보기") }
        }
    }

    val pickIndex = pickingFor
    if (pickIndex != null && pickIndex in steps.indices) {
        ActionPickerDialog(
            current = steps[pickIndex].action,
            onPick = { action ->
                steps = steps.replaceAt(pickIndex, steps[pickIndex].copy(action = action))
                pickingFor = null
            },
            onDismiss = { pickingFor = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StepEditor(
    index: Int,
    step: ScriptStep,
    nameA: String,
    nameB: String?,
    isFirst: Boolean,
    isLast: Boolean,
    onPerformerChange: (Performer) -> Unit,
    onPickAction: () -> Unit,
    onDurationChange: (Long) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("${index + 1}번째", style = MaterialTheme.typography.labelLarge)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Performer.entries.forEach { performer ->
                FilterChip(
                    selected = step.performer == performer,
                    onClick = { onPerformerChange(performer) },
                    label = { Text(performerLabel(performer, nameA, nameB)) }
                )
            }
        }

        OutlinedButton(onClick = onPickAction, modifier = Modifier.fillMaxWidth()) {
            Text(actionLabel(step.action))
        }

        LabeledSlider(
            label = "길이",
            value = if (step.durationMs == 0L) 0 else (step.durationMs / 100L).toInt().coerceIn(0, 50),
            range = 0..50,
            suffix = if (step.durationMs == 0L) " (기본)" else "×0.1초"
        ) { onDurationChange(it * 100L) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMoveUp, enabled = !isFirst) { Text("위로") }
            OutlinedButton(onClick = onMoveDown, enabled = !isLast) { Text("아래로") }
            OutlinedButton(onClick = onRemove) { Text("빼기") }
        }
    }
}

@Composable
private fun ActionPickerDialog(
    current: CharacterAction,
    onPick: (CharacterAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("무엇을 할까요") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(SCENE_ACTIONS) { action ->
                    TextButton(
                        onClick = { onPick(action) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (action == current) "${actionLabel(action)}  ✓" else actionLabel(action)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    val copy = toMutableList()
    val tmp = copy[a]
    copy[a] = copy[b]
    copy[b] = tmp
    return copy
}
