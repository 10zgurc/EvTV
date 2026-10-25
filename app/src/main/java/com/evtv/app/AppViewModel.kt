package com.evtv.app

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Login, Home, Player }

const val CAT_ALL = "__all"
const val CAT_FAV = "__fav"

private const val EPG_MAX_AGE = 15 * 60_000L

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val cache = ChannelCache(app)
    private var api: XtreamApi? = null
    private var loadJob: Job? = null

    var screen by mutableStateOf(if (prefs.hasAccount) Screen.Home else Screen.Login)
        private set
    /** İlk yükleme (ekranda henüz liste yok) */
    var loading by mutableStateOf(false)
        private set
    /** Liste görünürken arka planda güncelleme */
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var serverCategories by mutableStateOf<List<Category>>(emptyList())
    private var allChannels by mutableStateOf<List<Channel>>(emptyList())

    var selectedCategoryId by mutableStateOf(prefs.lastCategory.ifBlank { CAT_ALL })
        private set
    var favorites by mutableStateOf(prefs.favorites)
        private set

    /** Saat ve ilerleme çubukları için her 20 sn güncellenen "şimdi". */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    val categories by derivedStateOf {
        if (allChannels.isEmpty()) emptyList()
        else listOf(Category(CAT_ALL, "Tüm kanallar"), Category(CAT_FAV, "Favoriler")) + serverCategories
    }

    /** Kanallar bir kez kategorilere ayrılır; kategori değiştirmek anında olur (60 bin+ kanalda bile). */
    private val channelsByCategory by derivedStateOf {
        allChannels.groupBy { it.categoryId ?: "" }
    }

    private val favoriteChannels by derivedStateOf {
        allChannels.filter { it.streamId in favorites }
    }

    val visibleChannels by derivedStateOf {
        when (selectedCategoryId) {
            CAT_ALL -> allChannels
            CAT_FAV -> favoriteChannels
            else -> channelsByCategory[selectedCategoryId] ?: emptyList()
        }
    }

    // ---- Oynatıcı durumu ----
    var playList by mutableStateOf<List<Channel>>(emptyList())
        private set
    var playingIndex by mutableIntStateOf(0)
        private set
    val playing: Channel? get() = playList.getOrNull(playingIndex)
    /** Geri tuşuyla dönülecek bir önceki kanal (kumandada "son kanal") */
    private var previousChannel: Channel? = null

    // ---- EPG ----
    val epg = mutableStateMapOf<Int, List<EpgItem>>()
    private val epgFetchedAt = HashMap<Int, Long>()
    private val epgLoading = HashSet<Int>()

    // ---- Ekran geçişlerinde odak ----
    var lastChannelId: Int? = null
        private set
    /** Ana ekrana dönünce odağın nereye konacağı */
    var pendingFocus by mutableStateOf(FocusTarget.Category)

    val savedServer get() = prefs.server
    val savedUser get() = prefs.user
    val streamExt get() = prefs.streamExt

    init {
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(20_000)
            }
        }
        if (prefs.hasAccount) {
            api = XtreamApi(prefs.server, prefs.user, prefs.pass)
            load(useCache = true)
        }
    }

    fun login(serverInput: String, userInput: String, pass: String) {
        val parsed = parseServerInput(serverInput)
        val user = (parsed.user ?: userInput).trim()
        val password = parsed.pass ?: pass
        if (parsed.server.isBlank() || user.isBlank()) {
            error = "Sunucu adresi ve kullanıcı adı gerekli."
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val a = XtreamApi(parsed.server, user, password)
                val r = a.login()
                if (r.ok) {
                    val sameAccount = prefs.server == a.base && prefs.user == user
                    if (!sameAccount) {
                        // Başka hesaba geçildi: eski önbellek ve kategori seçimi geçersiz
                        cache.clear()
                        selectedCategoryId = CAT_ALL
                        prefs.lastCategory = CAT_ALL
                    }
                    prefs.server = a.base
                    prefs.user = user
                    prefs.pass = password
                    prefs.streamExt = r.preferredExt
                    api = a
                    pendingFocus = FocusTarget.Category
                    screen = Screen.Home
                    load(useCache = false)
                } else {
                    error = r.message
                    loading = false
                }
            } catch (e: Exception) {
                error = "Sunucuya bağlanılamadı: ${friendly(e)}"
                loading = false
            }
        }
    }

    fun load(useCache: Boolean = false) {
        val a = api ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            error = null
            if (useCache && allChannels.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { cache.load(prefs.accountKey) }
                if (cached != null) applyLists(cached.first, cached.second)
            }
            val hadList = allChannels.isNotEmpty()
            if (hadList) refreshing = true else loading = true
            try {
                val (cats, chans) = coroutineScope {
                    val c = async { a.liveCategories() }
                    val s = async { a.liveStreams() }
                    c.await() to s.await()
                }
                if (chans.isEmpty() && hadList) {
                    // Sunucu geçici olarak boş döndüyse elimizdeki listeyi silme
                    error = "Sunucu boş liste döndü, kayıtlı liste gösteriliyor."
                } else {
                    applyLists(cats, chans)
                    withContext(Dispatchers.IO) { cache.save(prefs.accountKey, cats, chans) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (hadList) "Liste güncellenemedi, kayıtlı liste gösteriliyor."
                else "Kanallar yüklenemedi: ${friendly(e)}"
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    private fun applyLists(cats: List<Category>, chans: List<Channel>) {
        serverCategories = cats
        allChannels = chans
        val valid = selectedCategoryId == CAT_ALL || selectedCategoryId == CAT_FAV ||
            cats.any { it.id == selectedCategoryId }
        if (!valid) selectCategory(CAT_ALL)
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "sunucu adresi bulunamadı (internet bağlantını ve adresi kontrol et)"
        is java.net.SocketTimeoutException -> "sunucu yanıt vermedi (zaman aşımı)"
        is java.net.ConnectException -> "bağlantı kurulamadı (port veya adres yanlış olabilir)"
        is javax.net.ssl.SSLException -> "güvenli bağlantı hatası (adresi http:// ile dene)"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun countFor(catId: String): Int = when (catId) {
        CAT_ALL -> allChannels.size
        CAT_FAV -> favoriteChannels.size
        else -> channelsByCategory[catId]?.size ?: 0
    }

    fun selectCategory(id: String) {
        if (id == selectedCategoryId) return
        selectedCategoryId = id
        prefs.lastCategory = id
    }

    fun isFavorite(id: Int) = id in favorites

    fun toggleFavorite(id: Int) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.favorites = favorites
    }

    fun play(channel: Channel, list: List<Channel>) {
        playList = list
        playingIndex = list.indexOfFirst { it.streamId == channel.streamId }.coerceAtLeast(0)
        previousChannel = null
        lastChannelId = channel.streamId
        screen = Screen.Player
    }

    private fun setPlaying(index: Int) {
        val old = playing
        playingIndex = index
        if (old != null && old.streamId != playing?.streamId) previousChannel = old
        lastChannelId = playing?.streamId
    }

    fun zap(delta: Int) {
        if (playList.isEmpty()) return
        setPlaying((playingIndex + delta).mod(playList.size))
    }

    /** Bir önceki izlenen kanala dön (varsa). */
    fun previous(): Boolean {
        val p = previousChannel ?: return false
        val i = playList.indexOfFirst { it.streamId == p.streamId }
        if (i < 0) return false
        setPlaying(i)
        return true
    }

    /** Kumandadan numara girilince o kanala geç. */
    fun jumpToNumber(num: Int): Boolean {
        val i = playList.indexOfFirst { it.num == num }
        if (i >= 0) {
            setPlaying(i)
            return true
        }
        val j = allChannels.indexOfFirst { it.num == num }
        if (j < 0) return false
        selectCategory(CAT_ALL)
        playList = allChannels
        setPlaying(j)
        return true
    }

    fun backFromPlayer() {
        pendingFocus = FocusTarget.LastChannel
        screen = Screen.Home
    }

    fun streamUrl(ch: Channel, ext: String): String = api?.streamUrl(ch.streamId, ext) ?: ""

    fun loadEpg(id: Int, force: Boolean = false) {
        val a = api ?: return
        val t = System.currentTimeMillis()
        val cur = epg[id]
        val fresh = cur != null && !force &&
            t - (epgFetchedAt[id] ?: 0L) < EPG_MAX_AGE &&
            (cur.isEmpty() || cur.first().end > t)
        if (fresh || !epgLoading.add(id)) return
        viewModelScope.launch {
            try {
                epg[id] = a.shortEpg(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epg[id] == null) epg[id] = emptyList()
            } finally {
                epgFetchedAt[id] = System.currentTimeMillis()
                epgLoading.remove(id)
            }
        }
    }

    fun clearError() {
        error = null
    }

    fun logout() {
        loadJob?.cancel()
        prefs.clearAccount()
        cache.clear()
        api = null
        allChannels = emptyList()
        serverCategories = emptyList()
        playList = emptyList()
        epg.clear()
        epgFetchedAt.clear()
        error = null
        loading = false
        refreshing = false
        screen = Screen.Login
    }
}

enum class FocusTarget { Category, LastChannel, None }
