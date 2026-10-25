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

enum class Screen { Login, Home, Player, Detail }

enum class Section { Home, Live, Movies, Series, Settings }

const val CAT_ALL = "__all"
const val CAT_FAV = "__fav"
const val CAT_RECENT = "__recent"
const val CAT_SEARCH = "__search"
const val CAT_MYLIST = "__mylist"

private const val EPG_MAX_AGE = 15 * 60_000L

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val cache = ChannelCache(app)
    val watch = WatchStore(app)
    private val movieIndex = SearchIndex(app, "m")
    private val seriesIndex = SearchIndex(app, "s")
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
                        watch.clear()
                        movieIndex.clear()
                        seriesIndex.clear()
                        selectedCategoryId = CAT_ALL
                        prefs.lastCategory = CAT_ALL
                        prefs.lastMovieCat = ""
                        prefs.lastSeriesCat = ""
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

    private var autoStarted = false

    private fun applyLists(cats: List<Category>, chans: List<Channel>) {
        serverCategories = cats
        allChannels = chans
        if (!autoStarted && prefs.startLastChannel && screen == Screen.Home) {
            autoStarted = true
            chans.firstOrNull { it.streamId == prefs.lastChannelId }?.let { ch ->
                val list = chans.filter { it.categoryId == ch.categoryId }.ifEmpty { chans }
                play(ch, list)
            }
        }
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
        prefs.lastChannelId = channel.streamId
        screen = Screen.Player
    }

    private fun setPlaying(index: Int) {
        val old = playing
        playingIndex = index
        if (old != null && old.streamId != playing?.streamId) previousChannel = old
        lastChannelId = playing?.streamId
        playing?.let { prefs.lastChannelId = it.streamId }
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

    // =====================================================================
    // Filmler ve diziler
    // =====================================================================

    /** Uygulama her zaman Ana Sayfa ile açılır */
    var section by mutableStateOf(Section.Home)
        private set

    var movieCats by mutableStateOf<List<Category>>(emptyList())
        private set
    var seriesCats by mutableStateOf<List<Category>>(emptyList())
        private set
    var vodCatsLoading by mutableStateOf(false)
        private set
    var vodCatsError by mutableStateOf<String?>(null)
        private set

    var selectedMovieCat by mutableStateOf(prefs.lastMovieCat)
        private set
    var selectedSeriesCat by mutableStateOf(prefs.lastSeriesCat)
        private set

    /** Kategori başına yüklenen listeler: "m:<kategori>" / "s:<kategori>" */
    val vodItems = mutableStateMapOf<String, List<VodItem>>()
    val vodErrors = mutableStateMapOf<String, String>()
    private val vodLoading = HashSet<String>()

    /** İlerleme/izleme geçmişi değişince arayüz yeniden okusun diye. */
    var watchVersion by mutableIntStateOf(0)
        private set

    /** Ana ekrana dönünce odaklanılacak film/dizi. */
    var lastVodId: Int? = null
    /** Ana Sayfa'da en son tıklanan öğe (geri dönünce odak için) */
    var homeLastKey: String? = null
    var vodFocusPending by mutableStateOf(false)

    fun selectSection(s: Section) {
        if (section == s) return
        section = s
        if (s != Section.Settings) prefs.lastSection = s.name
        if (s == Section.Movies || s == Section.Series || s == Section.Home) ensureVodCategories()
    }

    fun vodCategoriesFor(series: Boolean): List<Category> {
        val cats = if (series) seriesCats else movieCats
        if (cats.isEmpty()) return emptyList()
        return listOf(
            Category(CAT_SEARCH, "Ara"),
            Category(CAT_RECENT, "İzlemeye devam et"),
            Category(CAT_MYLIST, "Listem"),
        ) + cats
    }

    fun selectedVodCat(series: Boolean) = if (series) selectedSeriesCat else selectedMovieCat

    fun selectVodCategory(series: Boolean, id: String) {
        if (series) {
            if (selectedSeriesCat == id) return
            selectedSeriesCat = id
            prefs.lastSeriesCat = id
        } else {
            if (selectedMovieCat == id) return
            selectedMovieCat = id
            prefs.lastMovieCat = id
        }
        when (id) {
            CAT_RECENT, CAT_MYLIST -> {}
            CAT_SEARCH -> prepareSearch(series)
            else -> loadVodItems(series, id)
        }
    }

    fun ensureVodCategories(force: Boolean = false) {
        val a = api ?: return
        if (vodCatsLoading) return
        if (!force && movieCats.isNotEmpty() && seriesCats.isNotEmpty()) return
        viewModelScope.launch {
            vodCatsLoading = true
            vodCatsError = null
            try {
                coroutineScope {
                    val m = async { runCatching { a.vodCategories() } }
                    val se = async { runCatching { a.seriesCategories() } }
                    val mr = m.await()
                    val sr = se.await()
                    mr.getOrNull()?.let { movieCats = it }
                    sr.getOrNull()?.let { seriesCats = it }
                    val ex = mr.exceptionOrNull() ?: sr.exceptionOrNull()
                    if (ex is Exception && (movieCats.isEmpty() || seriesCats.isEmpty())) {
                        vodCatsError = "Kategoriler yüklenemedi: ${friendly(ex)}"
                    }
                }
                // Kayıtlı kategori artık yoksa "İzlemeye devam et" ya da ilk kategori
                fun pick(cur: String, cats: List<Category>, series: Boolean): String = when {
                    cur == CAT_RECENT && watch.recents(series).isNotEmpty() -> cur
                    cur == CAT_MYLIST && watch.myList(series).isNotEmpty() -> cur
                    cats.any { it.id == cur } -> cur
                    watch.recents(series).isNotEmpty() -> CAT_RECENT
                    else -> cats.firstOrNull()?.id ?: ""
                }
                selectedMovieCat = pick(selectedMovieCat, movieCats, false).also { prefs.lastMovieCat = it }
                selectedSeriesCat = pick(selectedSeriesCat, seriesCats, true).also { prefs.lastSeriesCat = it }
                if (selectedMovieCat.isNotBlank() && !selectedMovieCat.startsWith("__")) loadVodItems(false, selectedMovieCat)
                if (selectedSeriesCat.isNotBlank() && !selectedSeriesCat.startsWith("__")) loadVodItems(true, selectedSeriesCat)
                // Ana sayfadaki "yeni" satırları
                featuredCategory(false)?.let { loadVodItems(false, it) }
                featuredCategory(true)?.let { loadVodItems(true, it) }
            } finally {
                vodCatsLoading = false
            }
        }
    }

    fun vodKey(series: Boolean, cat: String) = (if (series) "s:" else "m:") + cat

    fun visibleVod(series: Boolean): List<VodItem>? {
        val cat = selectedVodCat(series)
        if (cat == CAT_RECENT) {
            watchVersion // değişiklikleri izle
            return watch.recents(series)
        }
        if (cat == CAT_SEARCH) return searchResults[series] ?: emptyList()
        if (cat == CAT_MYLIST) {
            watchVersion
            return watch.myList(series)
        }
        return vodItems[vodKey(series, cat)]
    }

    fun loadVodItems(series: Boolean, cat: String, force: Boolean = false) {
        val a = api ?: return
        val key = vodKey(series, cat)
        if ((!force && vodItems.containsKey(key)) || !vodLoading.add(key)) return
        vodErrors.remove(key)
        viewModelScope.launch {
            try {
                vodItems[key] = if (series) a.seriesList(cat) else a.vodStreams(cat)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                vodErrors[key] = "Liste yüklenemedi: ${friendly(e)}"
            } finally {
                vodLoading.remove(key)
            }
        }
    }

    // ---- Detay ekranı ----

    var detailItem by mutableStateOf<VodItem?>(null)
        private set
    var movieInfo by mutableStateOf<MovieInfo?>(null)
        private set
    var seriesInfo by mutableStateOf<SeriesInfo?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var selectedSeason by mutableIntStateOf(1)
    private var detailJob: Job? = null

    fun openDetail(item: VodItem) {
        lastVodId = item.id
        detailItem = item
        movieInfo = null
        seriesInfo = null
        screen = Screen.Detail
        loadDetail()
    }

    fun loadDetail() {
        val a = api ?: return
        val item = detailItem ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            detailLoading = true
            detailError = null
            try {
                if (item.isSeries) {
                    val info = a.seriesInfo(item.id)
                    seriesInfo = info
                    // Son izlenen bölümün sezonunu aç
                    val last = watch.lastEpisode(item.id)
                    val lastSeason = info.seasons.entries.firstOrNull { e -> e.value.any { it.id == last } }?.key
                    selectedSeason = lastSeason ?: info.seasons.keys.firstOrNull() ?: 1
                } else {
                    movieInfo = a.movieInfo(item.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                detailError = "Bilgiler yüklenemedi: ${friendly(e)}"
            } finally {
                detailLoading = false
            }
        }
    }

    fun backFromDetail() {
        detailJob?.cancel()
        vodFocusPending = true
        screen = Screen.Home
    }

    fun removeFromRecents(item: VodItem) {
        watch.forget(item)
        watchVersion++
    }

    // ---- Film/dizi oynatma (ayrı oynatıcı ekranında) ----

    /** Oynatıcıya verilecek sıra: film için tek öğe, dizi için seçilen bölümden sona kadar. */
    var vodQueue by mutableStateOf<List<VodPlay>>(emptyList())
        private set
    /** Her artışta oynatıcı ekranı açılır. */
    var vodLaunchToken by mutableIntStateOf(0)
        private set
    var vodLaunchHandled = 0

    fun playMovie(fromStart: Boolean) {
        val a = api ?: return
        val item = detailItem ?: return
        val ext = movieInfo?.ext ?: item.ext
        val key = "m${item.id}"
        watch.touch(item.copy(ext = ext))
        watchVersion++
        vodQueue = listOf(
            VodPlay(
                key = key,
                title = item.name,
                subtitle = movieInfo?.year,
                url = a.movieUrl(item.id, ext),
                startMs = if (fromStart) 0L else watch.position(key),
                hasNext = false,
                meta = cleanTitle(item.name).let { (t, y) -> SubMeta(t, movieInfo?.year ?: y, movieInfo?.tmdb) },
            )
        )
        vodLaunchToken++
    }

    fun allEpisodes(): List<Episode> =
        seriesInfo?.seasons?.values?.flatten() ?: emptyList()

    /** Dizide "devam et": yarım kalan ya da sıradaki izlenmemiş bölüm. */
    fun resumeEpisode(): Episode? {
        val item = detailItem ?: return null
        val eps = allEpisodes()
        if (eps.isEmpty()) return null
        val last = watch.lastEpisode(item.id)
        val i = eps.indexOfFirst { it.id == last }
        if (i < 0) return eps.first()
        return if (watch.isDone("e${eps[i].id}")) eps.getOrNull(i + 1) ?: eps[i] else eps[i]
    }

    fun playEpisode(ep: Episode, fromStart: Boolean = false) {
        val a = api ?: return
        val item = detailItem ?: return
        val eps = allEpisodes()
        val start = eps.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
        val rest = eps.drop(start).ifEmpty { listOf(ep) }
        vodQueue = rest.mapIndexed { i, e ->
            val key = "e${e.id}"
            VodPlay(
                key = key,
                title = item.name,
                subtitle = "S${e.season} B${e.num} • ${e.title}",
                url = a.episodeUrl(e.id, e.ext),
                startMs = if (i == 0 && !fromStart) watch.position(key) else 0L,
                hasNext = i < rest.size - 1,
                meta = SubMeta(cleanTitle(seriesInfo?.name ?: item.name).first, null, seriesInfo?.tmdb, e.season, e.num),
            )
        }
        onEpisodeStarted(ep.id)
        vodLaunchToken++
    }

    /** Oynatıcı yeni bölüme geçince çağrılır. */
    fun onEpisodeStarted(episodeId: String) {
        val item = detailItem ?: return
        watch.touch(item)
        watch.setLastEpisode(item.id, episodeId)
        allEpisodes().firstOrNull { it.id == episodeId }?.let { selectedSeason = it.season }
        watchVersion++
    }

    fun markDone(key: String) {
        watch.markDone(key)
        watchVersion++
    }

    fun savePosition(key: String, posMs: Long, durMs: Long) {
        watch.save(key, posMs, durMs)
        watchVersion++
    }

    fun onVodPlayerClosed() {
        vodQueue = emptyList()
        watchVersion++
    }

    // ---- Altyazı ayarları ----

    var subtitleLang by mutableStateOf(prefs.subtitleLang)
        private set
    var subtitleSize by mutableIntStateOf(prefs.subtitleSize)
        private set

    var subtitleLang2 by mutableStateOf(prefs.subtitleLang2)
        private set

    fun setSubtitleLanguage(code: String) {
        subtitleLang = code
        prefs.subtitleLang = code
    }

    fun setSubtitleLanguage2(code: String) {
        subtitleLang2 = code
        prefs.subtitleLang2 = code
    }

    fun cycleSubtitleSize() {
        subtitleSize = (subtitleSize + 1) % 3
        prefs.subtitleSize = subtitleSize
    }

    // ---- Arama ----

    data class IndexStatus(val building: Boolean, val progress: Int = 0, val error: String? = null)

    /** Anahtar: true = diziler, false = filmler */
    val searchQuery = mutableStateMapOf<Boolean, String>()
    val searchResults = mutableStateMapOf<Boolean, List<VodItem>>()
    val indexStatus = mutableStateMapOf<Boolean, IndexStatus>()
    var searching by mutableStateOf(false)
        private set
    private val searchJobs = HashMap<Boolean, Job>()

    private fun index(series: Boolean) = if (series) seriesIndex else movieIndex

    fun indexSize(series: Boolean) = index(series).size
    fun indexReady(series: Boolean) = index(series).exists

    /** Arama dizini yoksa ya da 3 günden eskiyse arka planda (yeniden) oluştur. */
    fun prepareSearch(series: Boolean, force: Boolean = false) {
        val idx = index(series)
        if (indexStatus[series]?.building == true) return
        if (!force && idx.exists && idx.ageMs < 3L * 24 * 3600_000) return
        val a = api ?: return
        viewModelScope.launch {
            indexStatus[series] = IndexStatus(building = true)
            try {
                idx.build(a) { n -> viewModelScope.launch { indexStatus[series] = IndexStatus(true, n) } }
                indexStatus.remove(series)
                // Dizin hazırlanırken bir şey yazıldıysa şimdi ara
                searchQuery[series]?.takeIf { it.isNotBlank() }?.let { setSearchQuery(series, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                indexStatus[series] = IndexStatus(false, 0, "Arama listesi indirilemedi: ${friendly(e)}")
            }
        }
    }

    fun setSearchQuery(series: Boolean, q: String) {
        searchQuery[series] = q
        searchJobs[series]?.cancel()
        if (q.isBlank()) {
            searchResults[series] = emptyList()
            return
        }
        if (!index(series).exists) return
        searchJobs[series] = viewModelScope.launch {
            delay(350)
            searching = true
            try {
                searchResults[series] = index(series).search(q)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                searchResults[series] = emptyList()
            } finally {
                searching = false
            }
        }
    }

    // ---- İnternetten altyazı ayarları ----

    var osKey by mutableStateOf(prefs.osKey)
        private set
    var osUser by mutableStateOf(prefs.osUser)
        private set
    var osPass by mutableStateOf(prefs.osPass)
        private set
    var osAuto by mutableStateOf(prefs.osAuto)
        private set
    var osTestResult by mutableStateOf<String?>(null)
        private set

    fun updateOs(key: String = osKey, user: String = osUser, pass: String = osPass) {
        if (key != osKey) { osKey = key; prefs.osKey = key }
        if (user != osUser) { osUser = user; prefs.osUser = user }
        if (pass != osPass) { osPass = pass; prefs.osPass = pass }
        osTestResult = null
    }

    fun toggleOsAuto() {
        osAuto = !osAuto
        prefs.osAuto = osAuto
    }

    fun testOpenSubtitles() {
        if (!prefs.osConfigured) {
            osTestResult = "Önce üç alanı da doldur."
            return
        }
        osTestResult = "Deneniyor…"
        viewModelScope.launch {
            osTestResult = try {
                OpenSubtitles(getApplication<Application>(), prefs).token(forceNew = true)
                "✓ Bağlantı başarılı. Altyazılar artık otomatik bulunacak."
            } catch (e: Exception) {
                "✗ " + (e.message ?: "Bağlanılamadı")
            }
        }
    }

    var bufferLevel by mutableIntStateOf(prefs.bufferLevel)
        private set

    fun cycleBufferLevel() {
        bufferLevel = (bufferLevel + 1) % 3
        prefs.bufferLevel = bufferLevel
    }

    // ---- Hata raporları ----

    /** Ekranda gösterilecek çökme/donma raporu (null = gösterme) */
    var reportText by mutableStateOf(EvTvApplication.takeUnseenReport(app)?.let { EvTvApplication.shorten(it) })
        private set

    fun showReports() {
        reportText = EvTvApplication.allReports(getApplication<Application>())?.let { EvTvApplication.shorten(it) }
            ?: "Kayıtlı hata raporu yok. 👍"
    }

    fun closeReport() {
        reportText = null
    }

    // ---- Listem / Ana Sayfa yardımcıları ----

    fun inMyList(item: VodItem): Boolean {
        watchVersion
        return watch.inMyList(item)
    }

    fun toggleMyList(item: VodItem): Boolean {
        val r = watch.toggleMyList(item)
        watchVersion++
        return r
    }

    /** Oynatıcıdaki kanal listesinden seçim */
    fun playAt(index: Int) {
        if (index in playList.indices) setPlaying(index)
    }

    val favoriteChannelList: List<Channel> get() = favoriteChannels

    var startLastChannel by mutableStateOf(prefs.startLastChannel)
        private set

    fun toggleStartLastChannel() {
        startLastChannel = !startLastChannel
        prefs.startLastChannel = startLastChannel
    }

    /** Ana sayfadaki "yeni" satırı için uygun kategori: adında yeni/new/son geçen, yoksa ilki */
    fun featuredCategory(series: Boolean): String? {
        val cats = if (series) seriesCats else movieCats
        val re = Regex("(yeni|new|son ekle|latest|recent|2026|2025)", RegexOption.IGNORE_CASE)
        return (cats.firstOrNull { re.containsMatchIn(it.name) } ?: cats.firstOrNull())?.id
    }

    /** Ana sayfada odaklanılan öğenin arka plan resmi (film detayından, önbellekli) */
    val heroBackdrops = mutableStateMapOf<String, String>()
    private val heroLoading = HashSet<String>()

    fun loadHeroBackdrop(item: VodItem) {
        val a = api ?: return
        val key = (if (item.isSeries) "s" else "m") + item.id
        if (heroBackdrops.containsKey(key) || !heroLoading.add(key)) return
        viewModelScope.launch {
            try {
                val bd = if (item.isSeries) a.seriesInfo(item.id).backdrop else a.movieInfo(item.id).backdrop
                heroBackdrops[key] = bd ?: item.poster ?: ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                heroBackdrops[key] = item.poster ?: ""
            } finally {
                heroLoading.remove(key)
            }
        }
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
        movieCats = emptyList()
        seriesCats = emptyList()
        vodItems.clear()
        vodErrors.clear()
        watch.clear()
        movieIndex.clear()
        seriesIndex.clear()
        searchResults.clear()
        error = null
        loading = false
        refreshing = false
        screen = Screen.Login
    }
}

enum class FocusTarget { Category, LastChannel, None }

/** Oynatılacak film veya bölüm. */
data class VodPlay(
    val key: String,
    val title: String,
    val subtitle: String?,
    val url: String,
    val startMs: Long,
    val hasNext: Boolean,
    val meta: SubMeta? = null,
)
