package com.evtv.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Ana sayfanın üst kısmında gösterilen öğe */
private sealed class Hero {
    data class Vod(val item: VodItem) : Hero()
    data class Live(val ch: Channel) : Hero()
    data class Tile(val title: String, val text: String) : Hero()
}

private data class TileSpec(val key: String, val icon: String, val title: String, val text: String, val c1: Color, val c2: Color)

@Composable
fun HomeDashboard(vm: AppViewModel, tabReq: FocusRequester) {
    val reqs = remember { HashMap<String, FocusRequester>() }
    var hero by remember { mutableStateOf<Hero>(Hero.Tile("Hoş geldin", "Canlı TV, filmler ve diziler tek yerde.")) }
    vm.watchVersion // izleme geçmişi değişince satırlar yenilensin

    val recents = vm.watch.allRecents().take(30)
    val favChannels = vm.favoriteChannelList.take(30)
    val myList = vm.watch.allMyList().take(30)
    val newMovies = vm.featuredCategory(false)?.let { vm.vodItems[vm.vodKey(false, it)] }?.take(30).orEmpty()
    val newSeries = vm.featuredCategory(true)?.let { vm.vodItems[vm.vodKey(true, it)] }?.take(30).orEmpty()

    val tiles = listOf(
        TileSpec("t_live", "📺", "Canlı TV", "${vm.countFor(CAT_ALL)} kanal", Color(0xFF2B6BFF), Color(0xFF1B3FA8)),
        TileSpec("t_guide", "📅", "Rehber", "Saat saat yayın akışı", Color(0xFFF59E0B), Color(0xFF9A5B00)),
        TileSpec("t_movies", "🎬", "Filmler", "Kategoriler, arama, listem", Color(0xFFE5484D), Color(0xFF8E1F2B)),
        TileSpec("t_series", "🍿", "Diziler", "Sezonlar ve bölümler", Color(0xFF8B5CF6), Color(0xFF4C2A9E)),
        TileSpec("t_search", "🔍", "Ara", "Film veya dizi bul", Color(0xFF14B8A6), Color(0xFF0B6B61)),
        TileSpec("t_settings", "⚙️", "Ayarlar", "Altyazı, oynatma, hesap", Color(0xFF475569), Color(0xFF1E293B)),
    )

    // Odak: açılışta ilk kutu, geri dönünce son tıklanan öğe
    LaunchedEffect(Unit) {
        val back = vm.vodFocusPending || vm.pendingFocus == FocusTarget.LastChannel
        val startup = vm.pendingFocus != FocusTarget.None
        vm.vodFocusPending = false
        if (back) {
            vm.pendingFocus = FocusTarget.None
            val k = vm.homeLastKey
            if (k != null && focusWhenReady { reqs[k] }) return@LaunchedEffect
        }
        if (back || startup) {
            vm.pendingFocus = FocusTarget.None
            focusWhenReady { reqs["t_live"] }
        }
    }

    // Odaklanılan filmin büyük arka plan resmini biraz bekleyip yükle
    LaunchedEffect(hero) {
        val h = hero
        if (h is Hero.Vod) {
            delay(500)
            vm.loadHeroBackdrop(h.item)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ---- Arka plan görseli ----
        val heroImage: String? = when (val h = hero) {
            is Hero.Vod -> vm.heroBackdrops[(if (h.item.isSeries) "s" else "m") + h.item.id]?.ifBlank { null } ?: h.item.poster
            else -> null
        }
        Crossfade(targetState = heroImage, label = "hero") { img ->
            if (img != null) {
                Box(Modifier.fillMaxWidth().height(330.dp)) {
                    AsyncImage(
                        model = img, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.55f),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.bg, C.bg.copy(alpha = 0.7f), Color.Transparent))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg))))
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            // ---- Üst bilgi alanı ----
            Box(Modifier.fillMaxWidth().height(150.dp).padding(start = 8.dp, top = 4.dp)) {
                when (val h = hero) {
                    is Hero.Vod -> HeroText(h.item.name, listOfNotNull(
                        if (h.item.isSeries) "Dizi" else "Film",
                        h.item.rating?.let { "★ $it" },
                    ).joinToString("  •  "), if (vm.inMyList(h.item)) "✓ Listende" else null)
                    is Hero.Live -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(h.ch, 160, 96)
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(h.ch.name, color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            EpgLines(vm.epg[h.ch.streamId], vm.now)
                        }
                    }
                    is Hero.Tile -> HeroText(h.title, h.text, null)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                // ---- Büyük kutular ----
                item(key = "tiles") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (t in tiles) {
                            BigTile(
                                t, Modifier.weight(1f), reg(reqs, t.key),
                                onFocus = { hero = Hero.Tile(t.title, t.text) },
                            ) {
                                vm.homeLastKey = t.key
                                // Açılan bölümde odak hazır olsun
                                vm.pendingFocus = FocusTarget.Category
                                when (t.key) {
                                    "t_live" -> vm.selectSection(Section.Live)
                                    "t_guide" -> vm.selectSection(Section.Guide)
                                    "t_movies" -> vm.selectSection(Section.Movies)
                                    "t_series" -> vm.selectSection(Section.Series)
                                    "t_search" -> {
                                        vm.selectSection(Section.Movies)
                                        vm.selectVodCategory(false, CAT_SEARCH)
                                    }
                                    else -> vm.selectSection(Section.Settings)
                                }
                            }
                        }
                    }
                }

                if (recents.isNotEmpty()) item(key = "r_recent") {
                    PosterRow("İzlemeye devam et", recents, "rec", vm, reqs, { hero = Hero.Vod(it) })
                }
                if (favChannels.isNotEmpty()) item(key = "r_fav") {
                    RowTitle("Favori kanallar")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        items(favChannels, key = { it.streamId }) { ch ->
                            val k = "fav_${ch.streamId}"
                            ChannelCard(ch, reg(reqs, k), onFocus = {
                                hero = Hero.Live(ch)
                                vm.loadEpg(ch.streamId)
                            }) {
                                vm.homeLastKey = k
                                vm.play(ch, favChannels)
                            }
                        }
                    }
                }
                if (myList.isNotEmpty()) item(key = "r_my") {
                    PosterRow("Listem", myList, "my", vm, reqs, { hero = Hero.Vod(it) })
                }
                if (newMovies.isNotEmpty()) item(key = "r_newm") {
                    PosterRow("Yeni filmler", newMovies, "nm", vm, reqs, { hero = Hero.Vod(it) })
                }
                if (newSeries.isNotEmpty()) item(key = "r_news") {
                    PosterRow("Yeni diziler", newSeries, "ns", vm, reqs, { hero = Hero.Vod(it) })
                }
                if (recents.isEmpty() && favChannels.isEmpty() && myList.isEmpty() && newMovies.isEmpty()) item(key = "hint") {
                    Text(
                        "İpucu: Canlı TV'de bir kanalda orta tuşu basılı tutarak favorilere, film sayfasında \"Listeme ekle\" ile listene ekleyebilirsin. Burada görünürler.",
                        color = C.sub, fontSize = 15.sp, modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Satır içindeki öğenin odak isteyicisini kaydeder (geri dönüşte odak için). */
@Composable
private fun reg(reqs: HashMap<String, FocusRequester>, key: String): FocusRequester {
    val fr = remember(key) { FocusRequester() }
    DisposableEffect(key) {
        reqs[key] = fr
        onDispose { if (reqs[key] === fr) reqs.remove(key) }
    }
    return fr
}

@Composable
private fun HeroText(title: String, meta: String, badge: String?) {
    Column(Modifier.fillMaxWidth(0.6f)) {
        Text(title, color = C.text, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 38.sp)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(meta, color = C.sub, fontSize = 17.sp)
            if (badge != null) {
                Text(badge, color = C.text, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp)
                    .clip(RoundedCornerShape(6.dp)).background(C.focus).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun RowTitle(t: String) {
    Text(t, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
}

@Composable
private fun PosterRow(
    title: String,
    list: List<VodItem>,
    prefix: String,
    vm: AppViewModel,
    reqs: HashMap<String, FocusRequester>,
    onFocusItem: (VodItem) -> Unit,
) {
    RowTitle(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list, key = { (if (it.isSeries) "s" else "m") + it.id }) { item ->
            val k = "${prefix}_${if (item.isSeries) "s" else "m"}${item.id}"
            val fr = reg(reqs, k)
            Box(Modifier.width(132.dp).onFocusChanged { if (it.hasFocus) onFocusItem(item) }) {
                PosterCard(
                    item = item,
                    progress = if (item.isSeries) 0f else vm.watch.progress("m${item.id}"),
                    modifier = Modifier.focusRequester(fr),
                    onClick = {
                        vm.homeLastKey = k
                        vm.openDetail(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun BigTile(t: TileSpec, modifier: Modifier, fr: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tile")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(118.dp)
            .focusRequester(fr)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tvClickable(onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Brush.linearGradient(listOf(t.c1, t.c2)))
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .padding(16.dp),
    ) {
        Text(t.icon, fontSize = 30.sp, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(t.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(t.text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, fr: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "ch")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(196.dp)
            .focusRequester(fr)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tvClickable(onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(
            Modifier.fillMaxWidth().height(110.dp).clip(shape).background(C.panel)
                .then(if (focused) Modifier.border(3.dp, C.focus, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Logo(ch, 150, 84)
        }
        Text(ch.name, color = if (focused) C.text else C.sub, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
    }
}
