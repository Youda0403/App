package com.pairplay.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pairplay.app.engine.AppCategory
import com.pairplay.app.engine.AppRules
import com.pairplay.app.ui.PairPlayUiState
import com.pairplay.app.ui.PairPlayViewModel

/**
 * 어떤 앱에서 캐릭터가 숨을지 정하는 화면.
 *
 * 은행·결제 앱은 미리 알아서 숨도록 해 두었고(기본), 사용자가 직접 바꿀 수 있다
 * (직접 정함). 직접 정한 것이 늘 우선이다. 숨는 앱을 위로 올려, 지금 어떤 앱에서
 * 숨는지 한눈에 보이게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(
    state: PairPlayUiState,
    viewModel: PairPlayViewModel,
    onBack: () -> Unit
) {
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<PairPlayViewModel.InstalledApp?>(null) }
    val overrides = remember(state.settings.appRules) { AppRules.parse(state.settings.appRules) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("숨을 앱 정하기") },
                navigationIcon = { IconButton(onClick = onBack) { Text("뒤로") } }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "은행·결제 앱은 알아서 숨도록 해 두었어요. 여기서 숨는 앱은 쓰는 동안 " +
                    "캐릭터가 연기와 함께 뿅 숨었다가, 나오면 다시 나타나요. " +
                    "눌러서 바꿀 수 있고, 직접 정한 게 늘 우선입니다.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("앱 이름으로 찾기") },
                modifier = Modifier.fillMaxWidth()
            )

            val list = apps
            if (list == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                val rows = list
                    .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                    .map { app -> app to AppRules.resolve(app.packageName, overrides) }
                    // 숨는 앱과 직접 정한 앱을 위로, 그다음은 이름 순.
                    .sortedWith(
                        compareBy(
                            { it.second == AppCategory.NONE && it.first.packageName !in overrides },
                            { it.first.label.lowercase() }
                        )
                    )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.first.packageName }) { (app, category) ->
                        AppRuleRow(
                            app = app,
                            category = category,
                            custom = app.packageName in overrides
                        ) { editing = app }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { app ->
        CategoryDialog(
            app = app,
            current = overrides[app.packageName],
            onPick = { category ->
                viewModel.setAppRule(app.packageName, category)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun AppRuleRow(
    app: PairPlayViewModel.InstalledApp,
    category: AppCategory,
    custom: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = remember(icon) { icon.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(40.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    custom -> "${category.label} · 직접 정함"
                    category == AppCategory.NONE -> "숨지 않음"
                    else -> "숨음 · 기본"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 한 앱에서 숨을지 고르는 창. '기본값으로' 를 누르면 직접 정한 것을 지운다. */
@Composable
private fun CategoryDialog(
    app: PairPlayViewModel.InstalledApp,
    current: AppCategory?,
    onPick: (AppCategory?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == category,
                            onClick = { onPick(category) }
                        )
                        Text(category.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(null) }) { Text("기본값으로") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
