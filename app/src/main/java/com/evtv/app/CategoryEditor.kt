package com.evtv.app

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Kategorileri göster/gizle ve sırala.
 * Orta tuş: göster/gizle. Basılı tut (veya Menü): taşıma modu → yukarı/aşağı taşı, orta tuş bırak.
 */
@Composable
fun CategoryEditor(vm: AppViewModel) {
    val type = vm.editorType
    val cats = vm.editorCategories(type)
    val hidden = vm.hiddenCats[type].orEmpty()
    var moving by remember { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstReq = remember { FocusRequester() }

    BackHandler {
        if (moving != null) moving = null else vm.closeCategoryEditor()
    }
    LaunchedEffect(type) {
        moving = null
        state.scrollToItem(0)
    }
    LaunchedEffect(Unit) { focusWhenReady { firstReq } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("Kategorileri düzenle", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Orta tuş: göster / gizle   •   Basılı tut veya Menü: taşı (yukarı/aşağı ile, bitince orta tuş)   •   Geri: kaydet ve çık",
            color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((t, label) in listOf("live" to "Canlı TV", "movie" to "Filmler", "series" to "Diziler")) {
                Box {
                    FocusRow(
                        modifier = if (t == "live") Modifier.focusRequester(firstReq) else Modifier,
                        fill = false, selected = t == type, onClick = { vm.switchEditorType(t) },
                    ) { f ->
                        Text(label, color = if (f || t == type) C.text else C.sub, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Box { FocusRow(fill = false, onClick = { vm.setAllHidden(type, false) }) { Text("Hepsini göster", color = C.sub, fontSize = 15.sp) } }
            Box { FocusRow(fill = false, onClick = { vm.setAllHidden(type, true) }) { Text("Hepsini gizle", color = C.sub, fontSize = 15.sp) } }
            Box { FocusRow(fill = false, onClick = { vm.resetOrder(type) }) { Text("Sırayı sıfırla", color = C.sub, fontSize = 15.sp) } }
        }
        Text(
            "${cats.size - cats.count { it.id in hidden }} / ${cats.size} kategori görünüyor",
            color = C.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp),
        )

        if (cats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (type != "live" && vm.vodCatsLoading) CircularProgressIndicator(color = C.accent)
                else Text("Kategori yok.", color = C.sub, fontSize = 17.sp)
            }
            return@Column
        }

        // Ülkelere ayrılabiliyorsa başlıklarla göster
        val groups = groupCategories(cats)
        val rows: List<Pair<CatGroup?, Category?>> =
            groups?.flatMap { g -> listOf<Pair<CatGroup?, Category?>>(g to null) + g.cats.map { g to it } }
                ?: cats.map { null to it }

        LazyColumn(state = state, modifier = Modifier.width(720.dp)) {
            items(rows, key = { (g, c) -> c?.id ?: ("g:" + g?.code) }) { (g, cat) ->
                if (cat == null && g != null) {
                    // ---- Ülke başlığı: tümünü gizle/göster ----
                    val ids = g.cats.map { it.id }
                    val hiddenN = ids.count { it in hidden }
                    FocusRow(
                        modifier = Modifier.padding(top = 10.dp),
                        onClick = { vm.setGroupHidden(type, ids, hide = hiddenN < ids.size) },
                    ) { f ->
                        Text(
                            when (hiddenN) {
                                0 -> "☑"
                                ids.size -> "☐"
                                else -> "◪"
                            },
                            color = if (hiddenN == ids.size) C.sub else C.accent, fontSize = 22.sp, modifier = Modifier.width(34.dp),
                        )
                        Text("${g.flag}  ${g.name}", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text(
                            if (hiddenN == ids.size) "tümü gizli" else "${ids.size - hiddenN}/${ids.size} görünüyor",
                            color = if (f) C.text else C.sub, fontSize = 14.sp,
                        )
                    }
                    return@items
                }
                cat ?: return@items
                val isHidden = cat.id in hidden
                val isMoving = moving == cat.id
                val groupIds = g?.cats?.map { it.id }
                FocusRow(
                    modifier = Modifier.padding(start = if (g != null) 24.dp else 0.dp).onPreviewKeyEvent { e ->
                        if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (e.nativeKeyEvent.keyCode) {
                            AndroidKey.KEYCODE_DPAD_UP -> -1
                            AndroidKey.KEYCODE_DPAD_DOWN -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        if (groupIds != null) vm.moveWithinGroup(type, cat.id, delta, groupIds)
                        else vm.moveCategory(type, cat.id, delta)
                        // Taşınan satır ekranda kalsın
                        scope.launch {
                            val ni = groupCategories(vm.editorCategories(type))
                                ?.flatMap { gg -> listOf<String?>(null) + gg.cats.map { it.id } }
                                ?.indexOf(cat.id) ?: vm.editorCategories(type).indexOfFirst { it.id == cat.id }
                            val vis = state.layoutInfo.visibleItemsInfo
                            if (vis.isNotEmpty() && (ni <= vis.first().index || ni >= vis.last().index)) {
                                state.scrollToItem((ni - 4).coerceAtLeast(0))
                            }
                        }
                        true
                    },
                    selected = isMoving,
                    onClick = { if (isMoving) moving = null else vm.toggleHidden(type, cat.id) },
                    onLongClick = { moving = if (isMoving) null else cat.id },
                ) { f ->
                    Text(if (isHidden) "☐" else "☑", color = if (isHidden) C.sub else C.accent, fontSize = 22.sp,
                        modifier = Modifier.width(34.dp))
                    Text(
                        if (g != null) stripPrefix(cat.name) else cat.name,
                        color = if (isHidden && !f) C.sub else C.text, fontSize = 17.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (type == "live") Text("${vm.channelCountIn(cat.id)}", color = C.sub, fontSize = 14.sp)
                    if (isMoving) Text("↕ taşınıyor", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    else if (isHidden) Text("gizli", color = C.sub, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
