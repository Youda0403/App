package com.pairplay.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pairplay.app.ui.theme.PairPlayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PairPlayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairPlayTheme {
                PairPlayNavHost(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 권한 설정 화면에 다녀왔을 수 있으므로 다시 읽는다.
        viewModel.refreshPermissions()
    }
}
