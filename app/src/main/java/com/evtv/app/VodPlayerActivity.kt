package com.evtv.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

/** Ana ekran ile film oynatıcısı arasındaki köprü. */
object PlayBridge {
    var vm: AppViewModel? = null
}

/**
 * Film ve dizi oynatıcısı. Android'in standart oynatıcı kontrollerini kullanır:
 * ilerleme çubuğu, duraklat, ileri/geri, altyazı (CC) ve ses dili menüsü.
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: TvPlayerView
    private lateinit var titleBox: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var messageText: TextView
    private var queue: List<VodPlay> = emptyList()
    private var hasError = false
    private val handler = Handler(Looper.getMainLooper())

    private val saver = object : Runnable {
        override fun run() {
            saveCurrent()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = PlayBridge.vm
        queue = vm?.vodQueue.orEmpty()
        if (vm == null || queue.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ---- Altyazı ve ses tercihleri ----
        val selector = DefaultTrackSelector(this)
        val params = selector.buildUponParameters()
        when (vm.subtitleLang) {
            "off" -> params.setPreferredTextLanguage(null)
            else -> params.setPreferredTextLanguage(vm.subtitleLang)
        }
        selector.setParameters(params)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            // TV'nin çözemediği Dolby/DTS seslerini yazılımla çöz
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        player = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(MC.USAGE_MEDIA).setContentType(MC.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()

        val items = queue.map { q ->
            MediaItem.Builder()
                .setUri(q.url)
                .setMediaId(q.key)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).setSubtitle(q.subtitle).build())
                .build()
        }
        player.setMediaItems(items, 0, queue.first().startMs.coerceAtLeast(0L))
        player.playWhenReady = true
        player.prepare()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val prevIndex = player.previousMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prevIndex != MC.INDEX_UNSET) {
                    // Önceki bölüm sonuna kadar izlendi
                    queue.getOrNull(prevIndex)?.let { PlayBridge.vm?.markDone(it.key) }
                }
                mediaItem?.mediaId?.let { id ->
                    if (id.startsWith("e")) PlayBridge.vm?.onEpisodeStarted(id.removePrefix("e"))
                }
                updateTitle()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) hideMessage()
                if (state == Player.STATE_ENDED) {
                    currentItem()?.let { PlayBridge.vm?.markDone(it.key) }
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showMessage(
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                            "Sunucu bu videoyu vermedi (bağlantı sınırı veya kaldırılmış olabilir)."
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Bağlantı koptu."
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                            "Bu videonun biçimi TV'nde desteklenmiyor."
                        else -> "Video açılamadı (${error.errorCodeName})."
                    } + "\nOrta tuş: tekrar dene  •  Geri: çık"
                )
            }
        })

        // ---- Görünüm ----
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        }
        subText = TextView(this).apply {
            setTextColor(0xFFB8C0CC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
            addView(titleText)
            addView(subText)
        }
        messageText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = GradientDrawable().apply {
                setColor(0xDD000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }

        view = TvPlayerView(this).apply {
            player = this@VodPlayerActivity.player
            useController = true
            controllerShowTimeoutMs = 4_000
            controllerAutoShow = true
            setShowSubtitleButton(true)
            setShowNextButton(queue.size > 1)
            setShowPreviousButton(queue.size > 1)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            onCenterWhenError = { if (hasError) retry() else false }
            subtitleView?.let { styleSubtitles(it, vm.subtitleSize) }
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                titleBox.visibility = v
            })
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(titleBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(messageText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        setContentView(root)
        updateTitle()
        view.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isControllerFullyVisible && !hasError) view.hideController() else finish()
            }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun currentItem(): VodPlay? = queue.getOrNull(player.currentMediaItemIndex)

    private fun updateTitle() {
        val q = currentItem() ?: return
        titleText.text = q.title
        subText.text = q.subtitle.orEmpty()
        subText.visibility = if (q.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showMessage(text: String) {
        hasError = true
        messageText.text = text
        messageText.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        hasError = false
        messageText.visibility = View.GONE
    }

    private fun retry(): Boolean {
        hideMessage()
        player.prepare()
        player.play()
        return true
    }

    private fun saveCurrent() {
        if (!::player.isInitialized) return
        val q = currentItem() ?: return
        val dur = player.duration
        if (dur == MC.TIME_UNSET || dur <= 0) return
        PlayBridge.vm?.savePosition(q.key, player.currentPosition, dur)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) handler.postDelayed(saver, 10_000)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saver)
        if (::player.isInitialized) {
            saveCurrent()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        PlayBridge.vm?.onVodPlayerClosed()
    }
}

/** Altyazılar: beyaz yazı, siyah kontur; boyut ayarlanabilir. */
@OptIn(UnstableApi::class)
private fun styleSubtitles(v: SubtitleView, size: Int) {
    v.setStyle(
        CaptionStyleCompat(
            Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
        )
    )
    val factor = when (size) {
        0 -> 0.85f
        2 -> 1.35f
        else -> 1.1f
    }
    v.setApplyEmbeddedFontSizes(false)
    v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * factor)
}

/**
 * Kumanda kısayolları (kontroller gizliyken):
 * Sol = 10 sn geri, Sağ = 30 sn ileri, Orta = duraklat/oynat.
 * Kontroller açıkken tuşlar normal şekilde düğmeler arasında gezer.
 */
@SuppressLint("ViewConstructor")
@OptIn(UnstableApi::class)
class TvPlayerView(ctx: Context) : PlayerView(ctx) {
    var onCenterWhenError: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isCenter && onCenterWhenError?.invoke() == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && !isControllerFullyVisible) {
            when {
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p.seekBack()
                    showController()
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p.seekForward()
                    showController()
                    return true
                }
                isCenter -> {
                    if (event.repeatCount == 0) {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                    showController()
                    return true
                }
            }
        }
        // Gizliyken orta tuşun bırakılması kontrol düğmesine basmasın
        if (event.action == KeyEvent.ACTION_UP && isCenter && !isControllerFullyVisible) return true
        return super.dispatchKeyEvent(event)
    }
}
