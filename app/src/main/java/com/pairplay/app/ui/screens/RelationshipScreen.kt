package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 관계와 행동 설정.
 *
 * 설계서 원칙 두 가지를 화면에서도 지킨다.
 * - 관계는 사용자가 정하며 앱이 자동으로 바꾸지 않는다.
 * - 사용자가 금지한 행동은 기본 프리셋보다 우선한다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RelationshipScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val pair = state.activePair
    val type = RelationshipType.fromName(pair?.relationship)
    val direction = RelationshipDirection.fromName(pair?.direction)

    var customLabel by remember(pair?.id) {
        mutableStateOf(pair?.customRelationshipLabel.orEmpty())
    }

    val a = state.characterA
    val b = state.characterB

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("관계와 행동") },
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

            if (a == null) {
                Text("먼저 캐릭터를 등록해 주세요.")
                return@Column
            }

            SectionCard("관계") {
                Text(
                    "고른 관계에 맞는 상황극이 나와요. 앱이 마음대로 관계를 바꾸지 않습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RelationshipType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = {
                                viewModel.setRelationship(candidate, direction, customLabel)
                            },
                            label = { Text(relationshipLabel(candidate)) }
                        )
                    }
                }
            }

            if (type == RelationshipType.CUSTOM) {
                SectionCard("관계 이름") {
                    OutlinedTextField(
                        value = customLabel,
                        onValueChange = {
                            customLabel = it
                            viewModel.setRelationship(type, direction, it)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (type.needsDirection) {
                SectionCard("누가 누구를") {
                    Text(
                        "마음을 품은 쪽이 몰래 바라보고 수줍어해요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectionChip(
                            label = "${a.name} → ${b?.name ?: "상대"}",
                            selected = direction == RelationshipDirection.A_TO_B
                        ) {
                            viewModel.setRelationship(
                                type,
                                RelationshipDirection.A_TO_B,
                                customLabel
                            )
                        }
                        DirectionChip(
                            label = "${b?.name ?: "상대"} → ${a.name}",
                            selected = direction == RelationshipDirection.B_TO_A
                        ) {
                            viewModel.setRelationship(
                                type,
                                RelationshipDirection.B_TO_A,
                                customLabel
                            )
                        }
                        DirectionChip(
                            label = "서로",
                            selected = direction == RelationshipDirection.MUTUAL
                        ) {
                            viewModel.setRelationship(
                                type,
                                RelationshipDirection.MUTUAL,
                                customLabel
                            )
                        }
                    }
                }
            }

            if (b == null) {
                SectionCard("짝") {
                    Text(
                        "지금은 한 명만 표시하고 있어요. 관계 설정은 두 명일 때 의미가 있습니다. " +
                            "캐릭터 관리에서 두 번째 캐릭터를 골라 주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            BlockedActionsCard(a, viewModel)
            if (b != null) {
                BlockedActionsCard(b, viewModel)
            }

            Text(
                "성격 수치는 캐릭터 관리 → 수정에서 바꿀 수 있어요. " +
                    "성격은 '어떤 장면이 자주 나오는지'에만 영향을 줍니다.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BlockedActionsCard(character: CharacterEntity, viewModel: PairPlayViewModel) {
    val blocked = remember(character.blockedActions) {
        character.blockedActions.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { id -> CharacterAction.entries.firstOrNull { it.id == id } }
            .toSet()
    }

    SectionCard("${character.name} 이(가) 하지 않을 행동") {
        Text(
            "고른 행동은 절대 하지 않아요. 그 행동이 들어간 상황극도 통째로 빠집니다.",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CONFIGURABLE_ACTIONS.forEach { action ->
                FilterChip(
                    selected = action in blocked,
                    onClick = {
                        val next = if (action in blocked) blocked - action else blocked + action
                        viewModel.setBlockedActions(character, next)
                    },
                    label = { Text(actionLabel(action)) }
                )
            }
        }
    }
}

private fun relationshipLabel(type: RelationshipType): String = when (type) {
    RelationshipType.LOVERS -> "연인"
    RelationshipType.FRIENDS -> "친구"
    RelationshipType.ONE_SIDED_LOVE -> "짝사랑"
    RelationshipType.RIVALS -> "라이벌"
    RelationshipType.FAMILY -> "가족"
    RelationshipType.COLLEAGUES -> "동료"
    RelationshipType.CUSTOM -> "직접 정하기"
}

/**
 * 사용자가 막을 수 있는 행동들.
 * 숨쉬기처럼 막으면 캐릭터가 멈춰 보이는 기본 동작은 뺐다.
 */
private val CONFIGURABLE_ACTIONS = listOf(
    CharacterAction.WALK,
    CharacterAction.JUMP,
    CharacterAction.APPROACH,
    CharacterAction.LOOK_AT,
    CharacterAction.GLANCE,
    CharacterAction.DOZE,
    CharacterAction.RHYTHM,
    CharacterAction.TEASE,
    CharacterAction.SHY,
    CharacterAction.REST,
    CharacterAction.GLARE,
    CharacterAction.LEAN
)

/** 화면에 보여 줄 이름. when 을 빠짐없이 써서 동작이 늘면 컴파일러가 알려 준다. */
private fun actionLabel(action: CharacterAction): String = when (action) {
    CharacterAction.IDLE -> "가만히 있기"
    CharacterAction.BREATHE -> "숨쉬기"
    CharacterAction.JUMP -> "점프"
    CharacterAction.WALK -> "걷기"
    CharacterAction.APPROACH -> "다가가기"
    CharacterAction.LOOK_AT -> "바라보기"
    CharacterAction.DOZE -> "졸기"
    CharacterAction.RHYTHM -> "리듬 타기"
    CharacterAction.SURPRISED -> "놀라기"
    CharacterAction.BUMP -> "부딪히기"
    CharacterAction.LEAN -> "기대기"
    CharacterAction.PET -> "쓰다듬김"
    CharacterAction.GLANCE -> "힐끗 보기"
    CharacterAction.TEASE -> "장난치기"
    CharacterAction.SHY -> "수줍어하기"
    CharacterAction.REST -> "나란히 쉬기"
    CharacterAction.GLARE -> "노려보기"
}
