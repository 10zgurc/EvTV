package com.evtv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EvTvApp() }
    }
}

@Composable
fun EvTvApp(vm: AppViewModel = viewModel()) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            background = C.bg,
            surface = C.bg,
            onSurface = C.text,
            onBackground = C.text,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = C.bg) {
            // Liste kaydırma konumları ekran değişse de korunur
            val catState = rememberLazyListState()
            val chState = rememberLazyListState()
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm, catState, chState)
                Screen.Player -> PlayerScreen(vm)
            }
        }
    }
}
