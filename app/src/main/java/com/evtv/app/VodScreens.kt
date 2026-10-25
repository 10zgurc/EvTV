package com.evtv.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// =====================================================================
// Ana ekran: üstte sekmeler, altta seçili bölüm
// =====================================================================

class HomeStates(
    val liveCats: LazyListState,
    val liveChannels: LazyListState,
    val movieCats: LazyListState,
    val movieGrid: LazyGridState,
    val seriesCats: LazyListState,
    val seriesGrid: LazyGridState,
)

@Composable
fun HomeScreen(vm: AppViewModel, st: HomeStates) {
    val tabReqs = remember { Section.entries.associateWith { FocusRequester() } }
    val tabReq = tabReqs.getValue(vm.section)

    LaunchedEffect(vm.section) {
        if (vm.section == Section.Movies || vm.section == Section.Series) vm.ensureVodCategories()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EvTV", color = C.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 20.dp))
            for (s in Section.entries) {
                val label = when (s) {
                    Section.Live -> "Canlı TV"
                    Section.Movies -> "Filmler"
                    Section.Series -> "Diziler"
                    Section.Settings -> "Ayarlar"
                }
                Box {
                    FocusRow(
                        modifier = Modifier.focusRequester(tabReqs.getValue(s)),
                        fill = false,
                        selected = vm.section == s,
                        onFocused = { vm.selectSection(s) },
                        onClick = { vm.selectSection(s) },
                    ) { f ->
                        Text(label, color = if (f || vm.section == s) C.text else C.sub, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (vm.refreshing || vm.vodCatsLoading) {
                CircularProgressIndicator(color = C.sub, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            Text(hm(vm.now), color = C.sub, fontSize = 20.sp, modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f)) {
            when (vm.section) {
                Section.Live -> LiveHome(vm, st.liveCats, st.liveChannels, tabReq)
                Section.Movies -> VodHome(vm, false, st.movieCats, st.movieGrid, tabReq)
                Section.Series -> VodHome(vm, true, st.seriesCats, st.seriesGrid, tabReq)
                Section.Settings -> SettingsPane(vm)
            }
        }
    }
}

// =====================================================================
// Film / dizi listesi
// =====================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodHome(vm: AppViewModel, series: Boolean, catState: LazyListState, grid: LazyGridState, tabReq: FocusRequester) {
    val cats = vm.vodCategoriesFor(series)
    val selected = vm.selectedVodCat(series)
    val list = vm.visibleVod(series)
    val key = vm.vodKey(series, selected)
    val catReq = remember { HashMap<String, FocusRequester>() }
    val itemReq = remember { HashMap<Int, FocusRequester>() }
    vm.watchVersion // ilerleme çubukları güncellensin

    // Kategori değişince ızgarayı başa sar
    var shownCat by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (shownCat != selected) {
            shownCat = selected
            grid.scrollToItem(0)
        }
    }

    // Detaydan dönünce aynı afişe, açılışta seçili kategoriye odaklan
    LaunchedEffect(cats.isNotEmpty(), list != null) {
        if (cats.isEmpty()) {
            if (vm.pendingFocus != FocusTarget.None) focusWhenReady { tabReq }
            return@LaunchedEffect
        }
        if (vm.vodFocusPending) {
            val l = list ?: return@LaunchedEffect
            vm.vodFocusPending = false
            val i = l.indexOfFirst { it.id == vm.lastVodId }
            if (i >= 0) {
                grid.scrollToItem(i)
                if (focusWhenReady { itemReq[l[i].id] }) return@LaunchedEffect
            }
        }
        if (vm.pendingFocus != FocusTarget.None) {
            vm.pendingFocus = FocusTarget.None
            val i = cats.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            catState.scrollToItem((i - 3).coerceAtLeast(0))
            focusWhenReady { catReq[cats[i].id] }
        }
    }

    if (cats.isEmpty()) {
        CenterBox {
            if (vm.vodCatsLoading) {
                CircularProgressIndicator(color = C.accent)
                Text("Kategoriler yükleniyor…", color = C.sub, modifier = Modifier.padding(top = 12.dp))
            } else {
                Text(vm.vodCatsError ?: "Bu hesapta ${if (series) "dizi" else "film"} bulunamadı.", color = C.sub, fontSize = 17.sp)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.width(260.dp)) {
                    FocusRow(onClick = { vm.ensureVodCategories(force = true) }) { Text("Tekrar dene", color = C.text) }
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(state = catState, modifier = Modifier.width(300.dp).fillMaxHeight().focusRestorer()) {
            items(cats, key = { it.id }) { cat ->
                val fr = remember { FocusRequester() }
                DisposableEffect(cat.id) {
                    catReq[cat.id] = fr
                    onDispose { if (catReq[cat.id] === fr) catReq.remove(cat.id) }
                }
                val isSel = cat.id == selected
                FocusRow(
                    modifier = Modifier.focusRequester(fr),
                    selected = isSel,
                    onFocused = { vm.selectVodCategory(series, cat.id) },
                    onClick = { vm.selectVodCategory(series, cat.id) },
                ) { f ->
                    Text(
                        when (cat.id) {
                            CAT_RECENT -> "▶ "
                            CAT_SEARCH -> "🔍 "
                            else -> ""
                        } + cat.name,
                        color = if (f || isSel) C.text else C.sub, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val n = when (cat.id) {
                        CAT_RECENT -> vm.watch.recents(series).size
                        CAT_SEARCH -> null
                        else -> vm.vodItems[vm.vodKey(series, cat.id)]?.size
                    }
                    if (n != null) Text("$n", color = if (f) C.text else C.sub, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                selected == CAT_SEARCH -> SearchPane(vm, series, list.orEmpty(), grid, itemReq)
                list == null && vm.vodErrors[key] != null -> CenterBox {
                    Text(vm.vodErrors[key] ?: "", color = C.error, fontSize = 17.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(260.dp)) {
                        FocusRow(onClick = { vm.loadVodItems(series, selected, force = true) }) {
                            Text("Tekrar dene", color = C.text)
                        }
                    }
                }
                list == null -> CenterBox {
                    CircularProgressIndicator(color = C.accent)
                    Text("Yükleniyor…", color = C.sub, modifier = Modifier.padding(top = 12.dp))
                }
                list.orEmpty().isEmpty() -> CenterBox {
                    Text(
                        if (selected == CAT_RECENT) "Henüz bir şey izlemedin." else "Bu kategori boş.",
                        color = C.sub, fontSize = 17.sp,
                    )
                }
                else -> PosterGrid(vm, series, list.orEmpty(), grid, itemReq, selected == CAT_RECENT)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PosterGrid(
    vm: AppViewModel,
    series: Boolean,
    list: List<VodItem>,
    grid: LazyGridState,
    itemReq: HashMap<Int, FocusRequester>,
    removable: Boolean,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        state = grid,
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(list, key = { it.id }) { item ->
            val fr = remember { FocusRequester() }
            DisposableEffect(item.id) {
                itemReq[item.id] = fr
                onDispose { if (itemReq[item.id] === fr) itemReq.remove(item.id) }
            }
            PosterCard(
                item = item,
                progress = if (series) 0f else vm.watch.progress("m${item.id}"),
                modifier = Modifier.focusRequester(fr),
                onClick = { vm.openDetail(item) },
                onLongClick = if (removable) ({ vm.removeFromRecents(item) }) else null,
            )
        }
    }
}

@Composable
fun PosterCard(
    item: VodItem,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "scale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick, onLongClick)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(C.panel)
                .then(if (focused) Modifier.border(3.dp, C.focus, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            var loaded by remember(item.poster) { mutableStateOf(false) }
            if (!loaded) {
                Text(
                    item.name, color = C.sub, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 4,
                    modifier = Modifier.padding(10.dp),
                )
            }
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true }, modifier = Modifier.fillMaxSize(),
                )
            }
            item.rating?.let {
                Text(
                    "★ $it", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(if (progress >= 1f) C.sub else C.accent))
                }
            }
        }
        Text(
            item.name, color = if (focused) C.text else C.sub, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

// =====================================================================
// Arama
// =====================================================================

@Composable
fun SearchPane(
    vm: AppViewModel,
    series: Boolean,
    results: List<VodItem>,
    grid: LazyGridState,
    itemReq: HashMap<Int, FocusRequester>,
) {
    val query = vm.searchQuery[series].orEmpty()
    val status = vm.indexStatus[series]
    val what = if (series) "dizi" else "film"
    Column(Modifier.fillMaxSize()) {
        TvField(
            label = "${what.replaceFirstChar { it.uppercase() }} adı yaz (Türkçe karakter şart değil)",
            value = query,
            onChange = { vm.setSearchQuery(series, it) },
            fm = androidx.compose.ui.platform.LocalFocusManager.current,
            imeSearch = true,
            modifier = Modifier.width(620.dp),
        )
        val info = when {
            status?.building == true && !vm.indexReady(series) ->
                "Arama için $what listesi ilk kez indiriliyor… ${if (status.progress > 0) "${status.progress} $what" else ""}\nBu sadece bir kez, 1-2 dakika sürer."
            status?.building == true -> "Liste arka planda güncelleniyor; arama çalışıyor."
            status?.error != null -> status.error
            query.isBlank() -> "${vm.indexSize(series)} $what içinde ara."
            vm.searching -> "Aranıyor…"
            results.isEmpty() -> "Sonuç bulunamadı."
            results.size >= 180 -> "İlk 180 sonuç gösteriliyor; aramayı daraltabilirsin."
            else -> "${results.size} sonuç"
        }
        Text(info, color = if (status?.error != null) C.error else C.sub, fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp, start = 4.dp))
        if (status?.error != null) {
            Box(Modifier.width(260.dp)) {
                FocusRow(onClick = { vm.prepareSearch(series, force = true) }) { Text("Tekrar dene", color = C.text) }
            }
        }
        if (results.isNotEmpty()) PosterGrid(vm, series, results, grid, itemReq, removable = false)
    }
}

// =====================================================================
// Ayarlar
// =====================================================================

/** Basınca altında dil listesi açılır; seçince kapanır. */
@Composable
private fun LanguagePicker(title: String, value: String, allowOff: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selReq = remember { FocusRequester() }
    FocusRow(onClick = { open = !open }) { f ->
        Text(title, color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(langName(value) + if (open) "  ▲" else "  ▼", color = if (f) C.text else C.accent, fontSize = 17.sp)
    }
    if (open) {
        val options = (if (allowOff) listOf("off" to "Kapalı") else emptyList()) + SUB_LANGS
        LaunchedEffect(Unit) { focusWhenReady { selReq } }
        Column(Modifier.padding(start = 28.dp)) {
            for ((code, name) in options) {
                FocusRow(
                    modifier = if (code == value) Modifier.focusRequester(selReq) else Modifier,
                    selected = code == value,
                    onClick = {
                        onPick(code)
                        open = false
                    },
                ) { f ->
                    Text(name, color = if (f || code == value) C.text else C.sub, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (code == value) Text("✓", color = C.text, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsPane(vm: AppViewModel) {
    Column(Modifier.width(640.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
        Text("Altyazı", color = C.accent, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
        LanguagePicker("Birinci altyazı dili", vm.subtitleLang, allowOff = true) { vm.setSubtitleLanguage(it) }
        if (vm.subtitleLang != "off") {
            LanguagePicker("Yedek dil (birincisi yoksa)", vm.subtitleLang2, allowOff = true) { vm.setSubtitleLanguage2(it) }
        }
        FocusRow(onClick = { vm.cycleSubtitleSize() }) { f ->
            Text("Altyazı boyutu", color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                when (vm.subtitleSize) {
                    0 -> "Küçük"
                    2 -> "Büyük"
                    else -> "Normal"
                },
                color = if (f) C.text else C.accent, fontSize = 17.sp,
            )
        }
        Text(
            "İzlerken altyazıyı veya ses dilini değiştirmek için orta tuşla kontrolleri aç, CC veya ayar (⚙) düğmesine bas.",
            color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 20.dp),
        )

        Text("İnternetten altyazı (OpenSubtitles.com)", color = C.accent, fontSize = 16.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
        Text(
            "Videoda seçtiğin dilde altyazı yoksa internetten otomatik bulur. opensubtitles.com'da ücretsiz hesap aç, " +
                "Profil → API Consumers bölümünden bir API anahtarı oluştur ve aşağıya gir. " +
                "İpucu: uzun anahtarı yazmak için telefondaki Google TV uygulamasının klavyesini kullanabilirsin.",
            color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
        )
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvField("API anahtarı", vm.osKey, { vm.updateOs(key = it) }, fm)
            TvField("OpenSubtitles kullanıcı adı", vm.osUser, { vm.updateOs(user = it) }, fm)
            TvField("OpenSubtitles şifresi", vm.osPass, { vm.updateOs(pass = it) }, fm,
                androidx.compose.ui.text.input.KeyboardType.Password, password = true, last = true)
        }
        FocusRow(onClick = { vm.toggleOsAuto() }) { f ->
            Text("Otomatik altyazı indir", color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(if (vm.osAuto) "Açık" else "Kapalı", color = if (f) C.text else C.accent, fontSize = 17.sp)
        }
        FocusRow(onClick = { vm.testOpenSubtitles() }) {
            Text("Bağlantıyı test et", color = C.text, fontSize = 17.sp)
        }
        vm.osTestResult?.let {
            Text(it, color = if (it.startsWith("✗")) C.error else C.sub, fontSize = 15.sp,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        }
        Text(
            "İzlerken menü tuşuna basarak ya da kontrollerin üstündeki \"İnternetten altyazı\" düğmesiyle elle de seçebilirsin.",
            color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 20.dp),
        )

        Text("Hesap", color = C.accent, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
        FocusRow(onClick = {
            if (!vm.refreshing) vm.load()
            vm.ensureVodCategories(force = true)
            vm.vodItems.clear()
        }) {
            Text(if (vm.refreshing) "Yenileniyor…" else "Tüm listeleri yenile", color = C.text, fontSize = 17.sp)
        }
        LogoutRow(vm)
        Spacer(Modifier.height(20.dp))
        Text("EvTV ${BuildConfig.VERSION_NAME}", color = C.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

// =====================================================================
// Film / dizi detayı
// =====================================================================

@Composable
fun DetailScreen(vm: AppViewModel) {
    val item = vm.detailItem ?: run {
        LaunchedEffect(Unit) { vm.backFromDetail() }
        return
    }
    BackHandler { vm.backFromDetail() }
    vm.watchVersion // dönüşte ilerleme güncellensin

    val movie = vm.movieInfo
    val ser = vm.seriesInfo
    val backdrop = movie?.backdrop ?: ser?.backdrop
    val poster = movie?.poster ?: ser?.poster ?: item.poster
    val firstReq = remember { FocusRequester() }
    val epReq = remember { HashMap<String, FocusRequester>() }
    val epState = rememberLazyListState()

    // Bilgiler gelince: dizide son izlenen bölüme, filmde oynat düğmesine odaklan
    LaunchedEffect(movie != null, ser != null, vm.detailError != null) {
        if (ser != null) {
            val last = vm.watch.lastEpisode(item.id)
            val eps = ser.seasons[vm.selectedSeason].orEmpty()
            val i = eps.indexOfFirst { it.id == last }
            if (i >= 0) {
                epState.scrollToItem((i - 2).coerceAtLeast(0))
                if (focusWhenReady { epReq[eps[i].id] }) return@LaunchedEffect
            }
        }
        focusWhenReady { firstReq }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.22f),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.bg, Color(0x990E1014), Color.Transparent))))

        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Box(
                Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.panel),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.name, color = C.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                if (poster != null) {
                    AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(ser?.name ?: item.name, color = C.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    movie?.year ?: ser?.year,
                    movie?.duration,
                    (movie?.rating ?: ser?.rating ?: item.rating)?.let { "★ $it" },
                    movie?.genre ?: ser?.genre,
                ).joinToString("  •  ")
                if (meta.isNotBlank()) Text(meta, color = C.sub, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))

                val plot = movie?.plot ?: ser?.plot
                if (plot != null) {
                    Text(plot, color = C.text, fontSize = 16.sp, maxLines = if (item.isSeries) 3 else 7,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                }
                val people = listOfNotNull(
                    (movie?.director)?.let { "Yönetmen: $it" },
                    (movie?.cast ?: ser?.cast)?.let { "Oyuncular: $it" },
                )
                for (p in people) {
                    Text(p, color = C.sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(Modifier.height(20.dp))

                when {
                    vm.detailLoading && movie == null && ser == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = C.accent, modifier = Modifier.size(28.dp))
                        Text("Bilgiler yükleniyor…", color = C.sub, modifier = Modifier.padding(start = 12.dp))
                        // Yüklenirken de filme başlanabilsin
                        if (!item.isSeries) {
                            Spacer(Modifier.width(24.dp))
                            ActionButton("▶  Oynat", Modifier.focusRequester(firstReq)) { vm.playMovie(fromStart = false) }
                        }
                    }
                    vm.detailError != null && movie == null && ser == null -> Column {
                        Text(vm.detailError ?: "", color = C.error, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                            ActionButton("Tekrar dene", Modifier.focusRequester(firstReq)) { vm.loadDetail() }
                            if (!item.isSeries) ActionButton("▶  Yine de oynat") { vm.playMovie(fromStart = false) }
                        }
                    }
                    !item.isSeries -> MovieActions(vm, item, firstReq)
                    ser != null -> SeriesPart(vm, item, ser, firstReq, epReq, epState)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box {
        FocusRow(modifier = modifier, fill = false, selected = false, onClick = onClick) {
            Text(text, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun MovieActions(vm: AppViewModel, item: VodItem, firstReq: FocusRequester) {
    val key = "m${item.id}"
    val pos = vm.watch.position(key)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pos > 0) {
            ActionButton("▶  Devam et (${fmtTime(pos)})", Modifier.focusRequester(firstReq)) { vm.playMovie(fromStart = false) }
            ActionButton("Baştan başla") { vm.playMovie(fromStart = true) }
        } else {
            ActionButton("▶  Oynat", Modifier.focusRequester(firstReq)) { vm.playMovie(fromStart = true) }
        }
    }
    if (vm.watch.isDone(key)) {
        Text("✓ İzlendi", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeriesPart(
    vm: AppViewModel,
    item: VodItem,
    ser: SeriesInfo,
    firstReq: FocusRequester,
    epReq: HashMap<String, FocusRequester>,
    epState: LazyListState,
) {
    if (ser.seasons.isEmpty()) {
        Text("Bu dizide bölüm bulunamadı.", color = C.sub, fontSize = 16.sp)
        return
    }
    val resume = vm.resumeEpisode()
    if (resume != null) {
        val started = vm.watch.lastEpisode(item.id) != null
        ActionButton(
            if (started) "▶  Devam et: S${resume.season} B${resume.num}" else "▶  İzlemeye başla",
            Modifier.focusRequester(firstReq),
        ) { vm.playEpisode(resume) }
    }

    // Sezonlar
    if (ser.seasons.size > 1) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 14.dp).focusRestorer(),
        ) {
            items(ser.seasons.keys.toList(), key = { it }) { s ->
                Box {
                    FocusRow(
                        fill = false,
                        selected = s == vm.selectedSeason,
                        onFocused = { vm.selectedSeason = s },
                        onClick = { vm.selectedSeason = s },
                    ) { f ->
                        Text("$s. Sezon", color = if (f || s == vm.selectedSeason) C.text else C.sub, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // Bölümler
    val eps = ser.seasons[vm.selectedSeason].orEmpty()
    LaunchedEffect(vm.selectedSeason) { epState.scrollToItem(0) }
    LazyColumn(state = epState, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().focusRestorer()) {
        items(eps, key = { it.id }) { ep ->
            val fr = remember { FocusRequester() }
            DisposableEffect(ep.id) {
                epReq[ep.id] = fr
                onDispose { if (epReq[ep.id] === fr) epReq.remove(ep.id) }
            }
            val key = "e${ep.id}"
            val prog = vm.watch.progress(key)
            FocusRow(modifier = Modifier.focusRequester(fr), onClick = { vm.playEpisode(ep) }) { f ->
                Text("${ep.num}", color = if (f) C.text else C.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, color = C.text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ep.plot != null) {
                        Text(ep.plot, color = if (f) C.text else C.sub, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (prog > 0f && prog < 1f) {
                        LinearProgressIndicator(
                            progress = { prog }, color = C.accent, trackColor = C.selected,
                            modifier = Modifier.fillMaxWidth(0.5f).padding(top = 4.dp),
                        )
                    }
                }
                if (prog >= 1f) Text("✓", color = if (f) C.text else C.sub, fontSize = 18.sp)
                ep.duration?.let { Text(it, color = if (f) C.text else C.sub, fontSize = 14.sp) }
            }
        }
    }
}
