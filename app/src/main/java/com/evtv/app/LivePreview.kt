package com.evtv.app

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Kanal listesinde odaklanılan kanalı küçük pencerede oynatır.
 * Hızlı gezinirken her kanal açılmasın diye kanalda ~1 sn durunca başlar.
 * (Tek bağlantılı hesaplarda da çalışır: aynı anda tek yayın açık olur.)
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePreview(vm: AppViewModel, ch: Channel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    // Kalıcı durum nesneleri (dinleyici her zaman güncelini görsün)
    var ext by remember { mutableStateOf(vm.streamExt) }
    var reload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var lastCh by remember { androidx.compose.runtime.mutableIntStateOf(-1) }

    val exo = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            // Önizleme hızlı başlasın
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000).build())
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ready = state == Player.STATE_READY || (ready && state == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: PlaybackException) {
                ready = false
                if (ext == "ts") {
                    ext = "m3u8"
                    reload++
                }
            }
        }
        exo.addListener(l)
        onDispose {
            exo.removeListener(l)
            exo.release()
        }
    }

    // Uygulama arka plana geçince dursun
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) exo.stop() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(ch.streamId, reload) {
        if (lastCh != ch.streamId) {
            lastCh = ch.streamId
            ext = vm.streamExt
        }
        ready = false
        exo.stop()
        delay(900)
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(vm.streamUrl(ch, ext))
                .setMimeType(if (ext == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
                .build()
        )
        exo.prepare()
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = exo
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ready) Logo(ch, 120, 72)
    }
}
