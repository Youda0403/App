package com.pairplay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pairplay.app.data.CharacterEntity
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun LabeledSwitch(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    Column {
        Text("$label  $value$suffix", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}

/** 캐릭터 이미지 미리보기. 파일이 사라졌어도 앱이 죽지 않게 한다. */
@Composable
fun CharacterThumbnail(
    character: CharacterEntity?,
    sizeDp: Int = 72,
    modifier: Modifier = Modifier
) {
    val file = character?.imagePath?.let { File(it) }
    if (character == null || file == null || !file.exists()) {
        Text(
            "이미지 없음",
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.size(sizeDp.dp).padding(top = (sizeDp / 3).dp)
        )
    } else {
        AsyncImage(
            model = file,
            contentDescription = character.name,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(sizeDp.dp)
        )
    }
}

/**
 * 캐릭터를 '화면에 뜨는 높이' 기준으로 그린다.
 * 가로는 원본 비율대로 따라가므로, 두 캐릭터를 나란히 놓으면
 * 실제 오버레이에서 보이는 크기 차이가 그대로 드러난다.
 */
@Composable
fun CharacterPreviewByHeight(
    character: CharacterEntity?,
    heightDp: Int,
    modifier: Modifier = Modifier
) {
    val file = character?.imagePath?.let { File(it) }
    if (character == null || file == null || !file.exists()) {
        Text(
            "이미지 없음",
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier
        )
        return
    }
    AsyncImage(
        model = file,
        contentDescription = character.name,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(heightDp.coerceAtLeast(1).dp)
            .wrapContentWidth()
    )
}
