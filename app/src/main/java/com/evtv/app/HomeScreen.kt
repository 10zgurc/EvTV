package com.evtv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

/** Lazy listede henüz çizilmemiş bir satıra odak verebilmek için birkaç kare bekleyip dener. */
private suspend fun focusWhenReady(get: () -> FocusRequester?): Boolean {
    repeat(20) {
        withFrameNanos { }
        val r = get()
        if (r != null && runCatching { r.requestFocus() }.isSuccess) return true
    }
    return false
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, catState: LazyListState, chState: LazyListState) {
    val channels = vm.visibleChannels
    val categories = vm.categories
    var focused by remember { mutableStateOf<Channel?>(null) }
    val catReq = remember { HashMap<String, FocusRequester>() }
    val chReq = remember { HashMap<Int, FocusRequester>() }
    val fallbackReq = remember { FocusRequester() }

    // Kategori değişince kanal listesini başa sar (ekrana ilk girişte değil)
    var shownCategory by remember { mutableStateOf(vm.selectedCategoryId) }
    LaunchedEffect(vm.selectedCategoryId) {
        if (shownCategory != vm.selectedCategoryId) {
            shownCategory = vm.selectedCategoryId
            chState.scrollToItem(0)
        }
    }

    // Açılışta veya oynatıcıdan dönünce odağı doğru yere koy
    LaunchedEffect(vm.pendingFocus, categories.isNotEmpty()) {
        if (categories.isEmpty()) {
            focusWhenReady { fallbackReq }
            return@LaunchedEffect
        }
        var done = false
        if (vm.pendingFocus == FocusTarget.LastChannel) {
            val i = channels.indexOfFirst { it.streamId == vm.lastChannelId }
            if (i >= 0) {
                chState.scrollToItem((i - 3).coerceAtLeast(0))
                done = focusWhenReady { chReq[channels[i].streamId] }
            }
        }
        if (!done && vm.pendingFocus != FocusTarget.None) {
            val i = categories.indexOfFirst { it.id == vm.selectedCategoryId }.coerceAtLeast(0)
            catState.scrollToItem((i - 3).coerceAtLeast(0))
            focusWhenReady { catReq[categories[i].id] }
        }
        vm.pendingFocus = FocusTarget.None
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        // ---- Sol: kategoriler ----
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row(
                Modifier.padding(start = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("EvTV", color = C.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (vm.refreshing) {
                    CircularProgressIndicator(color = C.sub, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
            }
            LazyColumn(state = catState, modifier = Modifier.weight(1f).focusRestorer()) {
                items(categories, key = { it.id }) { cat ->
                    val fr = remember { FocusRequester() }
                    DisposableEffect(cat.id) {
                        catReq[cat.id] = fr
                        onDispose { if (catReq[cat.id] === fr) catReq.remove(cat.id) }
                    }
                    val isSel = cat.id == vm.selectedCategoryId
                    FocusRow(
                        modifier = Modifier.focusRequester(fr),
                        selected = isSel,
                        onFocused = { vm.selectCategory(cat.id) },
                        onClick = { vm.selectCategory(cat.id) },
                    ) { f ->
                        Text(
                            (if (cat.id == CAT_FAV) "★ " else "") + cat.name,
                            color = if (f || isSel) C.text else C.sub,
                            fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${vm.countFor(cat.id)}", color = if (f) C.text else C.sub, fontSize = 14.sp)
                    }
                }
                if (categories.isNotEmpty()) {
                    item(key = "__actions") {
                        Column(Modifier.padding(top = 20.dp)) {
                            FocusRow(onClick = { if (!vm.refreshing) vm.load() }) {
                                Text(if (vm.refreshing) "Yenileniyor…" else "Listeyi yenile", color = C.sub, fontSize = 15.sp)
                            }
                            LogoutRow(vm)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // ---- Sağ: seçili kanal bilgisi + kanal listesi ----
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ChannelHeader(vm, focused)
            vm.error?.takeIf { channels.isNotEmpty() }?.let {
                Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            when {
                vm.loading && channels.isEmpty() -> CenterBox {
                    CircularProgressIndicator(color = C.accent)
                    Text("Kanallar yükleniyor…", color = C.sub, modifier = Modifier.padding(top = 12.dp))
                }
                vm.error != null && categories.isEmpty() -> CenterBox {
                    Text(vm.error ?: "", color = C.error, fontSize = 17.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(280.dp)) {
                        FocusRow(modifier = Modifier.focusRequester(fallbackReq), onClick = { vm.load() }) {
                            Text("Tekrar dene", color = C.text)
                        }
                    }
                    Box(Modifier.width(280.dp)) {
                        FocusRow(onClick = { vm.logout() }) { Text("Hesap bilgilerini değiştir", color = C.sub) }
                    }
                }
                channels.isEmpty() -> CenterBox {
                    Text(
                        if (vm.selectedCategoryId == CAT_FAV)
                            "Henüz favorin yok.\nBir kanalda orta tuşu basılı tut veya Menü tuşuna bas."
                        else "Bu kategoride kanal yok.",
                        color = C.sub, fontSize = 17.sp,
                    )
                }
                else -> LazyColumn(state = chState, modifier = Modifier.fillMaxSize().focusRestorer()) {
                    items(channels, key = { it.streamId }) { ch ->
                        val fr = remember { FocusRequester() }
                        DisposableEffect(ch.streamId) {
                            chReq[ch.streamId] = fr
                            onDispose { if (chReq[ch.streamId] === fr) chReq.remove(ch.streamId) }
                        }
                        ChannelRow(
                            ch = ch,
                            fav = vm.isFavorite(ch.streamId),
                            modifier = Modifier.focusRequester(fr),
                            onFocused = { focused = ch },
                            onClick = { vm.play(ch, channels) },
                            onLongClick = { vm.toggleFavorite(ch.streamId) },
                        )
                    }
                }
            }
        }
    }
}

/** Kazara çıkışı önlemek için iki kez basmak gerekir. */
@Composable
private fun LogoutRow(vm: AppViewModel) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    FocusRow(onClick = { if (armed) vm.logout() else armed = true }) {
        Text(
            if (armed) "Emin misin? Çıkmak için tekrar bas" else "Hesaptan çık",
            color = if (armed) C.error else C.sub, fontSize = 15.sp,
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    fav: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusRow(modifier = modifier, onFocused = onFocused, onClick = onClick, onLongClick = onLongClick) { f ->
        Text("${ch.num}", color = if (f) C.text else C.sub, fontSize = 15.sp, modifier = Modifier.width(52.dp))
        Logo(ch, 64, 40)
        Text(
            ch.name, color = C.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (fav) Text("★", color = C.star, fontSize = 18.sp)
    }
}

@Composable
fun Logo(ch: Channel, w: Int, h: Int) {
    Box(
        Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(C.panel),
        contentAlignment = Alignment.Center,
    ) {
        // Logo yoksa veya yüklenemezse kanal adının baş harfleri görünür
        var loaded by remember(ch.logo) { mutableStateOf(false) }
        if (!loaded) {
            Text(ch.name.take(2).uppercase(), color = C.sub, fontSize = (h / 3).sp, fontWeight = FontWeight.Bold)
        }
        if (ch.logo != null) {
            AsyncImage(
                model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        }
    }
}

/** Odaktaki kanalın adı ve şu an/sıradaki program. */
@Composable
private fun ChannelHeader(vm: AppViewModel, ch: Channel?) {
    LaunchedEffect(ch?.streamId) {
        if (ch != null) {
            delay(400) // hızlı gezinirken sunucuyu yormamak için
            vm.loadEpg(ch.streamId)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(C.panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ch == null) {
            Text("Bir kanal seç", color = C.sub, fontSize = 18.sp, modifier = Modifier.weight(1f))
        } else {
            Logo(ch, 120, 72)
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EpgLines(vm.epg[ch.streamId], vm.now)
            }
        }
        Text(hm(vm.now), color = C.sub, fontSize = 20.sp)
    }
}

@Composable
fun EpgLines(list: List<EpgItem>?, now: Long) {
    val cur = list?.firstOrNull { it.start <= now && it.end > now }
    val next = list?.firstOrNull { it.start > now }
    when {
        list == null -> Text("Yayın akışı yükleniyor…", color = C.sub, fontSize = 15.sp)
        cur == null && next == null -> Text("Yayın akışı yok", color = C.sub, fontSize = 15.sp)
        else -> {
            if (cur != null) {
                Text(
                    "Şimdi  ${hm(cur.start)}–${hm(cur.end)}  ${cur.title}", color = C.text, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val p = ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p }, color = C.accent, trackColor = C.selected,
                    modifier = Modifier.fillMaxWidth(0.6f).padding(vertical = 4.dp),
                )
            }
            if (next != null) {
                Text(
                    "Sonra  ${hm(next.start)}  ${next.title}", color = C.sub, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
