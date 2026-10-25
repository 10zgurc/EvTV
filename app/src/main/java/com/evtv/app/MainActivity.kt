package com.evtv.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EvTvApp() }
    }
}

@Composable
fun EvTvApp(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    PlayBridge.vm = vm

    // Film/bölüm seçilince ayrı oynatıcı ekranını aç
    LaunchedEffect(vm.vodLaunchToken) {
        if (vm.vodLaunchToken > vm.vodLaunchHandled) {
            vm.vodLaunchHandled = vm.vodLaunchToken
            ctx.startActivity(Intent(ctx, VodPlayerActivity::class.java))
        }
    }

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
            val liveCats = rememberLazyListState()
            val liveChannels = rememberLazyListState()
            val movieCats = rememberLazyListState()
            val movieGrid = rememberLazyGridState()
            val seriesCats = rememberLazyListState()
            val seriesGrid = rememberLazyGridState()
            val states = remember { HomeStates(liveCats, liveChannels, movieCats, movieGrid, seriesCats, seriesGrid) }
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm, states)
                Screen.Player -> PlayerScreen(vm)
                Screen.Detail -> DetailScreen(vm)
            }
        }
    }
}
