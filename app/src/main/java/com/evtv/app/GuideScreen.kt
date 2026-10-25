package com.evtv.app

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 4 * HALF_HOUR // ekranda 2 saat

private fun floorHalfHour(t: Long): Long {
    val tz = java.util.TimeZone.getDefault().getOffset(t)
    return ((t + tz) / HALF_HOUR) * HALF_HOUR - tz
}

/** Odaklanılan program bilgisi (üst şerit için) */
private data class GuideFocus(val ch: Channel, val item: EpgItem?)

/**
 * TiviMate tarzı yayın akışı ızgarası: solda kategoriler ve kanallar, sağda saat saat programlar.
 * Sağ/sol: programlar arasında (kenara gelince zaman kayar), yukarı/aşağı: kanallar.
 * Şu an yayında olan programa basınca kanal açılır.
 */
@Composable
fun GuideScreen(vm: AppViewModel, catState: LazyListState, tabReq: FocusRequester) {
    val categories = vm.categories
    val channels = vm.visibleChannels
    val minStart = floorHalfHour(vm.now)
    var windowStartRaw by remember { mutableLongStateOf(minStart) }
    // Zaman ilerledikçe geçmişte kalmasın
    val windowStart = maxOf(windowStartRaw, minStart)
    var info by remember { mutableStateOf<GuideFocus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val rowsState = rememberLazyListState()
    val catReq = remember { HashMap<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current
    val catDeb = rememberDebouncer<String> { vm.selectCategory(it) }

    var shownCat by remember { mutableStateOf(vm.selectedCategoryId) }
    LaunchedEffect(vm.selectedCategoryId) {
        if (shownCat != vm.selectedCategoryId) {
            shownCat = vm.selectedCategoryId
            rowsState.scrollToItem(0)
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_000)
            message = null
        }
    }
    LaunchedEffect(categories.isNotEmpty()) {
        if (vm.pendingFocus == FocusTarget.None) return@LaunchedEffect
        if (categories.isEmpty()) {
            focusWhenReady { tabReq }
            return@LaunchedEffect
        }
        vm.pendingFocus = FocusTarget.None
        val i = categories.indexOfFirst { it.id == vm.selectedCategoryId }.coerceAtLeast(0)
        catState.scrollToItem((i - 3).coerceAtLeast(0))
        focusWhenReady { catReq[categories[i].id] }
    }

    if (categories.isEmpty()) {
        CenterBox {
            CircularProgressIndicator(color = C.accent)
            Text("Kanallar yükleniyor…", color = C.sub, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // ---- Kategoriler ----
        LazyColumn(state = catState, modifier = Modifier.width(230.dp).fillMaxHeight().enterAt { catReq[vm.selectedCategoryId] }) {
            items(categories, key = { it.id }) { cat ->
                val fr = remember { FocusRequester() }
                DisposableEffect(cat.id) {
                    catReq[cat.id] = fr
                    onDispose { if (catReq[cat.id] === fr) catReq.remove(cat.id) }
                }
                val isSel = cat.id == vm.selectedCategoryId
                FocusRow(
                    modifier = Modifier.focusRequester(fr)
                        .flushOnKey(catDeb, AndroidKey.KEYCODE_DPAD_RIGHT, scope, fm, FocusDirection.Right),
                    selected = isSel,
                    onFocused = { catDeb.submit(cat.id) },
                    onClick = { catDeb.cancel(); vm.selectCategory(cat.id) },
                ) { f ->
                    Text(
                        (if (cat.id == CAT_FAV) "★ " else "") + cat.name,
                        color = if (f || isSel) C.text else C.sub, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // ---- Üst bilgi şeridi ----
            Column(
                Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                val inf = info
                when {
                    message != null -> Text(message ?: "", color = C.accent, fontSize = 17.sp)
                    inf == null -> Text("Bir programa gel: orta tuş ile şu an yayında olanı izle.", color = C.sub, fontSize = 16.sp)
                    else -> {
                        Text(
                            inf.item?.title ?: inf.ch.name, color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                inf.item?.let { "${dayLabel(it.start)} ${hm(it.start)}–${hm(it.end)}" },
                                inf.ch.name,
                                inf.item?.let { if (it.start <= vm.now && it.end > vm.now) "● Şimdi yayında" else null },
                            ).joinToString("  •  "),
                            color = C.sub, fontSize = 14.sp, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (channels.isEmpty()) {
                CenterBox { Text("Bu kategoride kanal yok.", color = C.sub, fontSize = 17.sp) }
                return@Column
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val chColW = 200.dp
                val timelineW = maxWidth - chColW
                // ---- Saat başlıkları ----
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(28.dp)) {
                        Text(
                            dayLabel(windowStart), color = C.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(chColW).padding(start = 6.dp),
                        )
                        Box(Modifier.width(timelineW).fillMaxHeight()) {
                            for (k in 0 until 4) {
                                val t = windowStart + k * HALF_HOUR
                                Text(
                                    hm(t), color = C.sub, fontSize = 14.sp,
                                    modifier = Modifier.offset(x = timelineW * (k / 4f)).padding(start = 4.dp),
                                )
                            }
                        }
                    }
                    LazyColumn(state = rowsState, modifier = Modifier.fillMaxSize()) {
                        items(channels, key = { it.streamId }) { ch ->
                            GuideRow(
                                vm, ch, windowStart, chColW, timelineW,
                                onShift = { delta ->
                                    val ns = (windowStart + delta).coerceIn(minStart, minStart + 20 * HALF_HOUR)
                                    val changed = ns != windowStart
                                    windowStartRaw = ns
                                    changed
                                },
                                onFocus = { info = it },
                                onPick = { item ->
                                    val now = vm.now
                                    if (item == null || (item.start <= now && item.end > now)) vm.play(ch, channels)
                                    else message = "Bu program ${dayLabel(item.start)} ${hm(item.start)}'de başlıyor."
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dayLabel(t: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = t
    return when (cal.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> "Bugün"
        1 -> "Yarın"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("tr")).format(java.util.Date(t))
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    ch: Channel,
    windowStart: Long,
    chColW: Dp,
    timelineW: Dp,
    onShift: (Long) -> Boolean,
    onFocus: (GuideFocus) -> Unit,
    onPick: (EpgItem?) -> Unit,
) {
    // Ekrana gelen satırların yayın akışı (hızlı kaydırırken istek atılmaz)
    LaunchedEffect(ch.streamId) {
        delay(350)
        vm.loadEpg(ch.streamId)
    }
    val windowEnd = windowStart + WINDOW
    val epg = vm.epg[ch.streamId]
    val visible = epg?.filter { it.end > windowStart && it.start < windowEnd }.orEmpty()

    Row(Modifier.fillMaxWidth().height(58.dp).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(chColW).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(C.panel).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Logo(ch, 52, 32)
            Text(ch.name, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(timelineW).fillMaxHeight().padding(start = 4.dp)) {
            if (visible.isEmpty()) {
                ProgramCell(
                    title = if (epg == null) "Yükleniyor…" else "Yayın akışı yok",
                    x = 0.dp, w = timelineW - 4.dp, live = true,
                    atLeftEdge = true, atRightEdge = true,
                    onShift = onShift, onFocus = { onFocus(GuideFocus(ch, null)) }, onPick = { onPick(null) },
                )
            } else {
                for (item in visible) {
                    val s = maxOf(item.start, windowStart)
                    val e = minOf(item.end, windowEnd)
                    val x = timelineW * ((s - windowStart).toFloat() / WINDOW)
                    val w = timelineW * ((e - s).toFloat() / WINDOW)
                    key(item.start) {
                        ProgramCell(
                            title = item.title,
                            x = x, w = (w - 3.dp).coerceAtLeast(6.dp),
                            live = item.start <= vm.now && item.end > vm.now,
                            atLeftEdge = item.start <= windowStart,
                            atRightEdge = item.end >= windowEnd,
                            onShift = onShift,
                            onFocus = { onFocus(GuideFocus(ch, item)) },
                            onPick = { onPick(item) },
                        )
                    }
                }
            }
            // Şu anki zaman çizgisi
            val nowX = timelineW * ((vm.now - windowStart).toFloat() / WINDOW)
            if (vm.now in windowStart until windowEnd) {
                Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(C.error))
            }
        }
    }
}

@Composable
private fun ProgramCell(
    title: String,
    x: Dp,
    w: Dp,
    live: Boolean,
    atLeftEdge: Boolean,
    atRightEdge: Boolean,
    onShift: (Long) -> Boolean,
    onFocus: () -> Unit,
    onPick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .offset(x = x)
            .width(w)
            .fillMaxHeight()
            // Kenardaki programda sağ/sol: zamanı kaydır (odak aynı programda kalır)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_RIGHT -> atRightEdge && onShift(HALF_HOUR)
                    AndroidKey.KEYCODE_DPAD_LEFT -> atLeftEdge && onShift(-HALF_HOUR)
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tvClickable(onPick)
            .clip(shape)
            .background(
                when {
                    focused -> C.focus
                    live -> Color(0xFF1F2A44)
                    else -> C.panel
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title, color = if (focused || live) C.text else C.sub, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
