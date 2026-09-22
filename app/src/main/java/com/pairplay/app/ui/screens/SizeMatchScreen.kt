package com.pairplay.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 두 캐릭터의 키를 맞추는 화면.
 *
 * 숫자만 보고 맞추기는 어려우므로, 실제 오버레이에서 보이는 비율 그대로
 * 나란히 놓고 보면서 조절하게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SizeMatchScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val a = state.characterA
    val b = state.characterB

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("키 맞추기") },
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
                Text("등록된 캐릭터가 없어요.")
                return@Column
            }

            SectionCard("나란히 보기") {
                SideBySidePreview(a, b)
                Text(
                    if (b == null) {
                        "${a.name}: ${a.displayHeightDp}dp"
                    } else if (a.displayHeightDp == b.displayHeightDp) {
                        "키가 같아요 (둘 다 ${a.displayHeightDp}dp). 둘 다 점선에 닿습니다."
                    } else {
                        "키가 달라요 — ${a.name} ${a.displayHeightDp}dp / " +
                            "${b.name} ${b.displayHeightDp}dp"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "실제 화면에 뜨는 비율 그대로예요. 발밑을 맞추고, 큰 쪽 머리 높이에 " +
                        "점선을 그어 두었습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (b != null) {
                SectionCard("한 번에 맞추기") {
                    val smaller = minOf(a.displayHeightDp, b.displayHeightDp)
                    val larger = maxOf(a.displayHeightDp, b.displayHeightDp)
                    val average = (a.displayHeightDp + b.displayHeightDp) / 2

                    Text(
                        "둘의 키를 같은 값으로 바꿔요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.matchHeights(smaller) },
                            enabled = smaller != larger
                        ) { Text("작은 쪽 (${smaller})") }
                        OutlinedButton(
                            onClick = { viewModel.matchHeights(larger) },
                            enabled = smaller != larger
                        ) { Text("큰 쪽 (${larger})") }
                    }
                    OutlinedButton(
                        onClick = { viewModel.matchHeights(average) },
                        enabled = smaller != larger
                    ) { Text("가운데로 (${average})") }
                }
            } else {
                SectionCard("한 번에 맞추기") {
                    Text(
                        "캐릭터가 한 명뿐이라 맞출 상대가 없어요. " +
                            "캐릭터 관리에서 두 번째 캐릭터를 골라 주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionCard("${a.name} 키") {
                LabeledSlider(
                    label = "높이",
                    value = a.displayHeightDp,
                    range = HEIGHT_RANGE,
                    suffix = " dp"
                ) { viewModel.updateCharacter(a.copy(displayHeightDp = it)) }
            }

            if (b != null) {
                SectionCard("${b.name} 키") {
                    LabeledSlider(
                        label = "높이",
                        value = b.displayHeightDp,
                        range = HEIGHT_RANGE,
                        suffix = " dp"
                    ) { viewModel.updateCharacter(b.copy(displayHeightDp = it)) }
                }
            }

            Text(
                "오버레이가 켜져 있으면 바꾸는 즉시 화면에도 반영돼요.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

/**
 * 두 캐릭터를 발밑 기준으로 나란히 놓고 실제 비율대로 보여 준다.
 *
 * 숫자만으로는 맞았는지 알기 어려워서, 큰 쪽 머리 높이에 기준선을 하나 긋는다.
 * 둘 다 선에 닿으면 키가 같은 것이고, 한쪽이 선 아래에 있으면 그만큼 작은 것이다.
 */
@Composable
private fun SideBySidePreview(a: CharacterEntity, b: CharacterEntity?) {
    val tallest = maxOf(a.displayHeightDp, b?.displayHeightDp ?: 0).coerceAtLeast(1)
    // 미리보기 영역에 맞춰 줄이되, 작은 캐릭터를 억지로 키우지는 않는다.
    val factor = (PREVIEW_MAX_DP.toFloat() / tallest).coerceAtMost(1f)
    val scaledTallest = tallest * factor

    val density = LocalDensity.current
    val guideColor = MaterialTheme.colorScheme.primary
    val floorColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((scaledTallest + FLOOR_INSET_DP + TOP_INSET_DP).dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val floorY = size.height - with(density) { FLOOR_INSET_DP.dp.toPx() }
            val topY = floorY - with(density) { scaledTallest.dp.toPx() }

            // 바닥선
            drawLine(
                color = floorColor,
                start = Offset(0f, floorY),
                end = Offset(size.width, floorY),
                strokeWidth = with(density) { 1.dp.toPx() }
            )
            // 큰 쪽 키 기준선
            drawLine(
                color = guideColor,
                start = Offset(0f, topY),
                end = Offset(size.width, topY),
                strokeWidth = with(density) { 1.dp.toPx() },
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(
                        with(density) { 6.dp.toPx() },
                        with(density) { 5.dp.toPx() }
                    )
                )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = FLOOR_INSET_DP.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            // 발밑을 맞춰야 키 차이가 제대로 보인다.
            verticalAlignment = Alignment.Bottom
        ) {
            CharacterPreviewByHeight(a, (a.displayHeightDp * factor).toInt())
            if (b != null) {
                CharacterPreviewByHeight(b, (b.displayHeightDp * factor).toInt())
            }
        }
    }
}

private val HEIGHT_RANGE = 60..320
private const val PREVIEW_MAX_DP = 200

/** 바닥선과 미리보기 아래 끝 사이 간격. 캐릭터는 이 바닥선 위에 선다. */
private const val FLOOR_INSET_DP = 10

/** 기준선이 잘리지 않도록 위쪽에 두는 여유. */
private const val TOP_INSET_DP = 10
