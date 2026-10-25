package com.evtv.app

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val MAX_RETRIES = 2

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val channel = vm.playing
    if (channel == null) {
        LaunchedEffect(Unit) { vm.backFromPlayer() }
        return
    }
    val ctx = LocalContext.current
    val currentChannel by rememberUpdatedState(channel)

    // Kanal başına deneme durumu (oynatıcı dinleyicisi de aynı nesneyi görür)
    val ps = remember { PlayAttempt() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingError by remember { mutableStateOf<PlaybackException?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    val exo = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(MC.USAGE_MEDIA).setContentType(MC.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    ps.retries = 0
                    errorText = null
                }
                // Canlı yayın "bitti" derse (sunucu bağlantıyı kesti) yeniden bağlan
                if (state == Player.STATE_ENDED) {
                    ps.retries = (ps.retries + 1).coerceAtMost(MAX_RETRIES)
                    reloadKey++
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                pendingError = error
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Uygulama arka plana geçince yayını durdur, dönünce canlı noktadan devam et
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> exo.stop()
                Lifecycle.Event.ON_START -> if (exo.currentMediaItem != null) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Yayını aç. Hızlı kanal değiştirirken sunucuya gereksiz bağlantı açmamak için kısa bekleme var.
    LaunchedEffect(channel.streamId, reloadKey) {
        if (ps.channelId != channel.streamId) {
            // Yeni kanal: önce sunucunun tercih ettiği biçim, olmazsa diğeri denenecek
            ps.channelId = channel.streamId
            ps.ext = vm.streamExt
            ps.triedOtherExt = false
            ps.retries = 0
        }
        errorText = null
        buffering = true
        exo.stop()
        delay(if (ps.retries > 0) 1_500L * ps.retries else 250L)
        val item = MediaItem.Builder()
            .setUri(vm.streamUrl(currentChannel, ps.ext))
            .setMimeType(if (ps.ext == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        vm.loadEpg(currentChannel.streamId)
    }

    // Hata olunca: önce aynı adresi birkaç kez dene, sonra diğer biçimi dene, en son mesaj göster
    LaunchedEffect(pendingError) {
        val err = pendingError ?: return@LaunchedEffect
        pendingError = null
        when {
            err.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                exo.seekToDefaultPosition()
                exo.prepare()
            }
            // Tek bağlantılı hesaplarda hızlı kanal değişiminde sunucu kısa süre 403 verebilir; bu yüzden HTTP hatası da yeniden denenir
            ps.retries < MAX_RETRIES -> {
                ps.retries++
                reloadKey++
            }
            !ps.triedOtherExt -> {
                ps.triedOtherExt = true
                ps.retries = 0
                ps.ext = if (ps.ext == "ts") "m3u8" else "ts"
                reloadKey++
            }
            else -> {
                buffering = false
                errorText = when (err.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Sunucu bu kanalı vermedi. Kanal kapalı olabilir veya bağlantı sınırına ulaşıldı."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "İnternet bağlantısı veya sunucu yanıt vermiyor."
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "Bu kanalın görüntü/ses biçimi TV'nde desteklenmiyor."
                    else -> "Yayın açılamadı (${err.errorCodeName})"
                }
            }
        }
    }

    LaunchedEffect(infoTick, channel.streamId) {
        showInfo = true
        delay(5_000)
        showInfo = false
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_000)
            toast = null
        }
    }

    // Numara girişi: 1.5 sn bekleyip o kanala geç
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1_500)
            val n = digits.toIntOrNull()
            digits = ""
            if (n != null && !vm.jumpToNumber(n)) toast = "$n numaralı kanal yok"
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BackHandler {
        when {
            digits.isNotEmpty() -> digits = ""
            else -> vm.backFromPlayer()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when (code) {
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP -> { vm.zap(1); true }
                    AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
                    AndroidKey.KEYCODE_LAST_CHANNEL, AndroidKey.KEYCODE_DPAD_LEFT -> {
                        if (!vm.previous()) toast = "Önceki kanal yok"
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER,
                    AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_INFO, AndroidKey.KEYCODE_DPAD_RIGHT -> {
                        if (errorText != null) {
                            // Hata ekranında orta tuş = tekrar dene
                            ps.retries = 0
                            ps.triedOtherExt = false
                            ps.ext = vm.streamExt
                            reloadKey++
                        } else infoTick++
                        true
                    }
                    AndroidKey.KEYCODE_MENU, AndroidKey.KEYCODE_BOOKMARK -> {
                        vm.toggleFavorite(channel.streamId)
                        toast = if (vm.isFavorite(channel.streamId)) "Favorilere eklendi" else "Favorilerden çıkarıldı"
                        true
                    }
                    in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> {
                        if (digits.length < 5) digits += (code - AndroidKey.KEYCODE_0).toString()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = exo
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && errorText == null) {
            CircularProgressIndicator(color = C.accent, modifier = Modifier.align(Alignment.Center))
        }
        errorText?.let {
            Text(
                "$it\n\nOrta tuş: tekrar dene  •  Yukarı/aşağı: başka kanal",
                color = C.text, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center).padding(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xDD000000)).padding(24.dp),
            )
        }

        if (digits.isNotEmpty()) {
            Text(
                digits, color = C.text, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }

        toast?.let {
            Text(
                it, color = C.text, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = showInfo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBar(vm, channel)
        }
    }
}

@Composable
private fun InfoBar(vm: AppViewModel, ch: Channel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Logo(ch, 120, 72)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.num}", color = C.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    ch.name, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (vm.isFavorite(ch.streamId)) Text("★", color = C.star, fontSize = 22.sp)
            }
            EpgLines(vm.epg[ch.streamId], vm.now)
        }
        Text(hm(vm.now), color = C.sub, fontSize = 20.sp)
    }
}

/** Bir kanalın açılma denemeleri: hangi biçim, kaç kez denendi. */
private class PlayAttempt {
    var channelId = -1
    var ext = "ts"
    var triedOtherExt = false
    var retries = 0
}
