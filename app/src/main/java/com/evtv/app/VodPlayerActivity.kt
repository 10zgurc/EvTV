package com.evtv.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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
    private lateinit var subPanel: LinearLayout
    private lateinit var subStatus: TextView
    private lateinit var subList: ListView
    private lateinit var subButton: TextView
    private lateinit var prefs: Prefs
    private lateinit var subs: OpenSubtitles
    private var subResults: List<SubResult> = emptyList()
    /** Otomatik altyazı kontrolü yapılmış öğeler */
    private val autoChecked = HashSet<String>()
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
        prefs = Prefs(this)
        subs = OpenSubtitles(this, prefs)

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
            // DefaultDataSource: hem internet hem de indirilen altyazı dosyası okunabilsin
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
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

            override fun onTracksChanged(tracks: Tracks) {
                if (!tracks.isEmpty) maybeAutoSubtitle(tracks)
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
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText)
            addView(subText)
        }
        subButton = TextView(this).apply {
            text = "🔍 İnternetten altyazı"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            background = buttonBg(false)
            setOnFocusChangeListener { v, has ->
                v.background = buttonBg(has)
                // Düğme seçiliyken kontroller kapanmasın
                view.controllerShowTimeoutMs = if (has) 0 else 4_000
                view.showController()
            }
            setOnClickListener { openSubtitlePanel() }
        }
        titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(48), dp(32), dp(48), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
            addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(subButton, LinearLayout.LayoutParams(-2, -2))
        }

        // ---- İnternetten altyazı paneli (sağda) ----
        subStatus = TextView(this).apply {
            setTextColor(0xFFB8C0CC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, dp(10))
        }
        subList = ListView(this).apply {
            divider = null
            selector = GradientDrawable().apply { setColor(0xFF2B6BFF.toInt()); cornerRadius = dp(8).toFloat() }
            setOnItemClickListener { _, _, pos, _ -> subResults.getOrNull(pos)?.let { pickSubtitle(it) } }
        }
        subPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xF0101318.toInt())
            visibility = View.GONE
            addView(TextView(this@VodPlayerActivity).apply {
                text = "İnternetten altyazı"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(subStatus)
            addView(subList, LinearLayout.LayoutParams(-1, 0, 1f))
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
            onMenu = { openSubtitlePanel() }
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
            addView(subPanel, FrameLayout.LayoutParams(dp(560), -1, Gravity.END))
        }
        setContentView(root)
        updateTitle()
        view.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    subPanel.visibility == View.VISIBLE -> closeSubtitlePanel()
                    view.isControllerFullyVisible && !hasError -> view.hideController()
                    else -> finish()
                }
            }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buttonBg(focused: Boolean) = GradientDrawable().apply {
        setColor(if (focused) 0xFF2B6BFF.toInt() else 0x66000000)
        cornerRadius = dp(10).toFloat()
        if (!focused) setStroke(dp(1), 0x88FFFFFF.toInt())
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------------- İnternetten altyazı ----------------

    private fun prefLang() = prefs.subtitleLang.takeIf { it != "off" } ?: "tr"

    /** Videonun içinde istenen dilde altyazı yoksa internetten otomatik bul. */
    private fun maybeAutoSubtitle(tracks: Tracks) {
        val q = currentItem() ?: return
        if (!autoChecked.add(q.key)) return
        if (prefs.subtitleLang == "off" || !prefs.osAuto) return
        val lang = prefLang()
        val hasEmbedded = tracks.groups.any { g ->
            g.type == MC.TRACK_TYPE_TEXT && (0 until g.length).any { i ->
                g.getTrackFormat(i).language?.lowercase()?.let { it == lang || it.startsWith("$lang-") || (lang == "tr" && it == "tur") || (lang == "en" && it == "eng") } == true
            }
        }
        if (hasEmbedded) return
        // Daha önce indirildiyse doğrudan ekle
        val cached = subs.cacheFile(q.key, lang)
        if (cached.exists()) {
            attachSubtitle(cached, lang, "İnternetten (${lang.uppercase()})")
            return
        }
        if (!prefs.osConfigured || q.meta == null) return
        lifecycleScope.launch {
            try {
                val best = subs.search(q.meta, listOf(lang)).firstOrNull { it.lang == lang } ?: return@launch
                val f = subs.download(best, cached)
                if (currentItem()?.key == q.key) {
                    attachSubtitle(f, lang, "İnternetten (${lang.uppercase()})")
                    toast("${langName(lang)} altyazı internetten eklendi")
                }
            } catch (e: Exception) {
                // Otomatik denemede sessiz kal; kullanıcı panelden elle deneyebilir
            }
        }
    }

    private fun langName(l: String) = when (l) {
        "tr" -> "Türkçe"
        "en" -> "İngilizce"
        else -> l.uppercase()
    }

    private fun openSubtitlePanel() {
        subPanel.visibility = View.VISIBLE
        view.hideController()
        subList.adapter = null
        subResults = emptyList()
        val q = currentItem()
        when {
            !prefs.osConfigured -> {
                subStatus.text = "Kullanmak için ana ekranda Ayarlar → İnternetten altyazı bölümüne ücretsiz OpenSubtitles.com " +
                    "hesabını ve API anahtarını gir.\n\nGeri: kapat"
                subPanel.requestFocus()
                return
            }
            q?.meta == null -> {
                subStatus.text = "Bu video için altyazı aranamıyor."
                return
            }
        }
        subStatus.text = "Aranıyor…"
        val meta = q!!.meta!!
        val first = prefLang()
        val langs = listOf(first, if (first == "tr") "en" else "tr")
        lifecycleScope.launch {
            try {
                subResults = subs.search(meta, langs).take(60)
                if (subResults.isEmpty()) {
                    subStatus.text = "Altyazı bulunamadı."
                    return@launch
                }
                subStatus.text = "Seç ve orta tuşa bas (${subResults.size} sonuç)"
                subList.adapter = object : ArrayAdapter<String>(
                    this@VodPlayerActivity, android.R.layout.simple_list_item_1,
                    subResults.map { r ->
                        val tags = listOfNotNull(
                            langName(r.lang),
                            if (r.machine) "otomatik çeviri" else null,
                            if (r.hearingImpaired) "işitme engelli" else null,
                            "${r.downloads} indirme",
                        ).joinToString(" • ")
                        "${r.release}\n$tags"
                    },
                ) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val v = super.getView(position, convertView, parent) as TextView
                        v.setTextColor(Color.WHITE)
                        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        v.maxLines = 3
                        return v
                    }
                }
                subList.requestFocus()
                subList.setSelection(0)
            } catch (e: Exception) {
                subStatus.text = e.message ?: "Altyazı aranamadı."
            }
        }
    }

    private fun closeSubtitlePanel() {
        subPanel.visibility = View.GONE
        view.requestFocus()
    }

    private fun pickSubtitle(r: SubResult) {
        val q = currentItem() ?: return
        subStatus.text = "İndiriliyor…"
        lifecycleScope.launch {
            try {
                val f = subs.download(r, java.io.File(cacheDir, "subs/${q.key}_${r.lang}_${r.fileId}.srt"))
                // Bir dahaki sefere otomatik bu kullanılsın
                f.copyTo(subs.cacheFile(q.key, r.lang), overwrite = true)
                attachSubtitle(f, r.lang, "${langName(r.lang)} (internet)")
                closeSubtitlePanel()
                toast("Altyazı eklendi")
            } catch (e: Exception) {
                subStatus.text = e.message ?: "İndirilemedi."
            }
        }
    }

    /** Altyazıyı mevcut videoya ekler ve seçer; video kaldığı yerden devam eder. */
    private fun attachSubtitle(file: java.io.File, lang: String, label: String) {
        val idx = player.currentMediaItemIndex
        val cur = player.currentMediaItem ?: return
        val pos = player.currentPosition
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(lang)
            .setLabel(label)
            .setSelectionFlags(MC.SELECTION_FLAG_DEFAULT)
            .build()
        val item = cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(lang)
            .build()
        player.replaceMediaItem(idx, item)
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
    }

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
    var onMenu: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isCenter && onCenterWhenError?.invoke() == true) return true
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS) {
            if (event.action == KeyEvent.ACTION_UP) onMenu?.invoke()
            return true
        }
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
