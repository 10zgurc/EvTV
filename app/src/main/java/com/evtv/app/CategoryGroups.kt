package com.evtv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// =====================================================================
// Kategori adlarından ülke tanıma: "TR | Spor", "|DE| Kino", "[UK] News", "RU: Кино" ...
// =====================================================================

private data class Country(val name: String, val flag: String)

/** Kanonik kod → ülke; takma adlar aşağıda */
private val COUNTRIES = mapOf(
    "TR" to Country("Türkiye", "🇹🇷"), "DE" to Country("Almanya", "🇩🇪"), "UK" to Country("İngiltere", "🇬🇧"),
    "US" to Country("ABD", "🇺🇸"), "FR" to Country("Fransa", "🇫🇷"), "IT" to Country("İtalya", "🇮🇹"),
    "ES" to Country("İspanya", "🇪🇸"), "PT" to Country("Portekiz", "🇵🇹"), "NL" to Country("Hollanda", "🇳🇱"),
    "BE" to Country("Belçika", "🇧🇪"), "CH" to Country("İsviçre", "🇨🇭"), "AT" to Country("Avusturya", "🇦🇹"),
    "PL" to Country("Polonya", "🇵🇱"), "RU" to Country("Rusya", "🇷🇺"), "UA" to Country("Ukrayna", "🇺🇦"),
    "AR" to Country("Arapça", "🌙"), "IR" to Country("İran", "🇮🇷"), "KU" to Country("Kürtçe", "☀️"),
    "AZ" to Country("Azerbaycan", "🇦🇿"), "GR" to Country("Yunanistan", "🇬🇷"), "AL" to Country("Arnavutluk", "🇦🇱"),
    "BG" to Country("Bulgaristan", "🇧🇬"), "RO" to Country("Romanya", "🇷🇴"), "HU" to Country("Macaristan", "🇭🇺"),
    "CZ" to Country("Çekya", "🇨🇿"), "SK" to Country("Slovakya", "🇸🇰"), "EXYU" to Country("Eski Yugoslavya", "🇷🇸"),
    "RS" to Country("Sırbistan", "🇷🇸"), "HR" to Country("Hırvatistan", "🇭🇷"), "BA" to Country("Bosna", "🇧🇦"),
    "MK" to Country("Makedonya", "🇲🇰"), "SE" to Country("İsveç", "🇸🇪"), "NO" to Country("Norveç", "🇳🇴"),
    "DK" to Country("Danimarka", "🇩🇰"), "FI" to Country("Finlandiya", "🇫🇮"), "IE" to Country("İrlanda", "🇮🇪"),
    "CA" to Country("Kanada", "🇨🇦"), "MX" to Country("Meksika", "🇲🇽"), "LAT" to Country("Latin Amerika", "🌎"),
    "BR" to Country("Brezilya", "🇧🇷"), "IN" to Country("Hindistan", "🇮🇳"), "PK" to Country("Pakistan", "🇵🇰"),
    "AF" to Country("Afganistan", "🇦🇫"), "AFR" to Country("Afrika", "🌍"), "IL" to Country("İsrail", "🇮🇱"),
    "CN" to Country("Çin", "🇨🇳"), "JP" to Country("Japonya", "🇯🇵"), "KR" to Country("Kore", "🇰🇷"),
    "PH" to Country("Filipinler", "🇵🇭"), "TH" to Country("Tayland", "🇹🇭"), "VN" to Country("Vietnam", "🇻🇳"),
    "MY" to Country("Malezya", "🇲🇾"), "ID" to Country("Endonezya", "🇮🇩"), "AU" to Country("Avustralya", "🇦🇺"),
    "NZ" to Country("Yeni Zelanda", "🇳🇿"), "ZA" to Country("Güney Afrika", "🇿🇦"), "EG" to Country("Mısır", "🇪🇬"),
    "MA" to Country("Fas", "🇲🇦"), "DZ" to Country("Cezayir", "🇩🇿"), "TN" to Country("Tunus", "🇹🇳"),
    "SA" to Country("Suudi Arabistan", "🇸🇦"), "AE" to Country("BAE", "🇦🇪"), "QA" to Country("Katar", "🇶🇦"),
    "IQ" to Country("Irak", "🇮🇶"), "SY" to Country("Suriye", "🇸🇾"), "LB" to Country("Lübnan", "🇱🇧"),
    "CAR" to Country("Karayipler", "🏝️"), "ASIA" to Country("Asya", "🌏"), "GE" to Country("Gürcistan", "🇬🇪"),
    "AM" to Country("Ermenistan", "🇦🇲"), "LT" to Country("Litvanya", "🇱🇹"), "LV" to Country("Letonya", "🇱🇻"),
    "EE" to Country("Estonya", "🇪🇪"), "SI" to Country("Slovenya", "🇸🇮"), "CY" to Country("Kıbrıs", "🇨🇾"),
)

private val ALIASES = mapOf(
    "TUR" to "TR", "GER" to "DE", "DEU" to "DE", "EN" to "UK", "GB" to "UK", "ENG" to "UK", "USA" to "US",
    "FRA" to "FR", "ITA" to "IT", "ESP" to "ES", "SP" to "ES", "POR" to "PT", "NLD" to "NL", "HOL" to "NL",
    "BEL" to "BE", "SUI" to "CH", "AUT" to "AT", "POL" to "PL", "RUS" to "RU", "UKR" to "UA", "ARA" to "AR",
    "ARAB" to "AR", "IRN" to "IR", "PER" to "IR", "KUR" to "KU", "KURD" to "KU", "AZE" to "AZ", "GRE" to "GR",
    "ALB" to "AL", "BUL" to "BG", "ROM" to "RO", "HUN" to "HU", "CZE" to "CZ", "SVK" to "SK", "YU" to "EXYU",
    "SRB" to "RS", "CRO" to "HR", "BIH" to "BA", "SWE" to "SE", "NOR" to "NO", "DEN" to "DK", "FIN" to "FI",
    "IRE" to "IE", "CAN" to "CA", "MEX" to "MX", "LATAM" to "LAT", "LATINO" to "LAT", "BRA" to "BR", "IND" to "IN",
    "PAK" to "PK", "AFG" to "AF", "AFRICA" to "AFR", "ISR" to "IL", "CHN" to "CN", "JPN" to "JP", "KOR" to "KR",
    "AUS" to "AU", "EGY" to "EG", "MAR" to "MA", "KSA" to "SA", "UAE" to "AE", "IRQ" to "IQ", "GEO" to "GE",
    "ARM" to "AM", "CYP" to "CY", "KKTC" to "CY",
)

private val BRACKETED = Regex("""^\s*[\[|(]\s*([A-Za-z]{2,6})\s*[\]|)]""")
private val DELIMITED = Regex("""^\s*([A-Za-z]{2,6})\s*[|:\-–•★»]""")
private val SPACED = Regex("""^\s*([A-Z]{2,4})\s+\S""")

/** Kategori adındaki önek kodu (kanonik), yoksa null */
fun categoryPrefix(name: String): String? {
    val raw = (BRACKETED.find(name) ?: DELIMITED.find(name))?.groupValues?.get(1)?.uppercase()
        ?: SPACED.find(name)?.groupValues?.get(1)?.takeIf { (ALIASES[it] ?: it) in COUNTRIES }
        ?: return null
    return ALIASES[raw] ?: raw
}

/** Grup içinde gösterirken "TR | Spor" → "Spor" */
fun stripPrefix(name: String): String {
    val m = BRACKETED.find(name) ?: DELIMITED.find(name) ?: return name
    return name.substring(m.range.last + 1).trim().ifBlank { name }
}

data class CatGroup(val code: String, val name: String, val flag: String, val cats: List<Category>)

const val OTHER_GROUP = "~"

/** Kategorileri ülkelere ayırır. Anlamlı bir gruplama yoksa (2'den az ülke) null. */
fun groupCategories(cats: List<Category>): List<CatGroup>? {
    if (cats.size < 8) return null
    val byCode = LinkedHashMap<String, MutableList<Category>>()
    for (c in cats) byCode.getOrPut(categoryPrefix(c.name) ?: OTHER_GROUP) { ArrayList() }.add(c)
    // Tek kategorilik bilinmeyen önekler "Diğer"e
    val merged = LinkedHashMap<String, MutableList<Category>>()
    for ((code, list) in byCode) {
        val key = if (code != OTHER_GROUP && code !in COUNTRIES && list.size < 2) OTHER_GROUP else code
        merged.getOrPut(key) { ArrayList() }.addAll(list)
    }
    if (merged.keys.count { it != OTHER_GROUP } < 2) return null
    val collator = java.text.Collator.getInstance(java.util.Locale("tr"))
    return merged.map { (code, list) ->
        val c = COUNTRIES[code]
        when {
            code == OTHER_GROUP -> CatGroup(code, "Diğer", "📁", list)
            c != null -> CatGroup(code, c.name, c.flag, list)
            else -> CatGroup(code, code, "📺", list)
        }
    }.sortedWith(
        compareBy<CatGroup> {
            when {
                it.code == "TR" -> 0
                it.code == OTHER_GROUP -> 3
                it.code in COUNTRIES -> 1
                else -> 2
            }
        }.thenComparator { a, b -> collator.compare(a.name, b.name) }
    )
}

// =====================================================================
// İki aşamalı kategori listesi: önce ülkeler, sonra o ülkenin kategorileri
// =====================================================================

sealed class CatRow(val key: String) {
    class Back(val group: CatGroup) : CatRow("back")
    class Group(val group: CatGroup) : CatRow("g:" + group.code)
    class Cat(val cat: Category) : CatRow(cat.id)
}

/** Ekranda gösterilecek satırlar (odak ve kaydırma için ekranlar da kullanır) */
fun categoryRows(vm: AppViewModel, type: String, specials: List<Category>, cats: List<Category>, selected: String): List<CatRow> {
    val groups = if (vm.groupCats) groupCategories(cats) else null
    if (groups == null) return (specials + cats).map { CatRow.Cat(it) }
    val open = vm.openGroupFor(type) { groups.firstOrNull { g -> g.cats.any { it.id == selected } }?.code }
    val g = groups.firstOrNull { it.code == open }
    return if (g == null) specials.map { CatRow.Cat(it) } + groups.map { CatRow.Group(it) }
    else listOf(CatRow.Back(g)) + g.cats.map { CatRow.Cat(it) }
}

@Composable
fun CategoryColumn(
    vm: AppViewModel,
    type: String,
    specials: List<Category>,
    cats: List<Category>,
    selected: String,
    state: LazyListState,
    catReq: HashMap<String, FocusRequester>,
    deb: Debouncer<String>,
    label: (Category) -> String,
    count: (Category) -> Int?,
    onClickCat: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 17,
) {
    val rows = categoryRows(vm, type, specials, cats, selected)
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current
    val inGroup = rows.firstOrNull() is CatRow.Back

    fun focusKeyLater(key: String, index: Int) {
        scope.launch {
            state.scrollToItem((index - 3).coerceAtLeast(0))
            focusWhenReady { catReq[key] }
        }
    }

    fun closeGroup(code: String) {
        deb.cancel()
        vm.setOpenGroup(type, null)
        val top = categoryRows(vm, type, specials, cats, selected)
        focusKeyLater("g:$code", top.indexOfFirst { it.key == "g:$code" }.coerceAtLeast(0))
    }

    // Geri: önce ülke listesine dön
    BackHandler(enabled = inGroup) {
        (rows.firstOrNull() as? CatRow.Back)?.let { closeGroup(it.group.code) }
    }

    LazyColumn(state = state, modifier = modifier.enterAt { catReq[selected] ?: catReq[rows.firstOrNull()?.key ?: ""] }) {
        items(rows, key = { it.key }) { row ->
            val fr = remember { FocusRequester() }
            DisposableEffect(row.key) {
                catReq[row.key] = fr
                onDispose { if (catReq[row.key] === fr) catReq.remove(row.key) }
            }
            when (row) {
                is CatRow.Back -> FocusRow(
                    modifier = Modifier.focusRequester(fr),
                    onClick = { closeGroup(row.group.code) },
                ) { f ->
                    Text("‹  ${row.group.flag}  ${row.group.name}", color = if (f) C.text else C.accent,
                        fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                is CatRow.Group -> {
                    val hasSel = row.group.cats.any { it.id == selected }
                    FocusRow(
                        modifier = Modifier.focusRequester(fr),
                        selected = hasSel,
                        onClick = {
                            deb.cancel()
                            vm.setOpenGroup(type, row.group.code)
                            val first = row.group.cats.firstOrNull()?.id
                            if (first != null) focusKeyLater(first, 1)
                        },
                    ) { f ->
                        Text("${row.group.flag}  ${row.group.name}", color = if (f || hasSel) C.text else C.sub,
                            fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${row.group.cats.size} ›", color = if (f) C.text else C.sub, fontSize = 14.sp)
                    }
                }
                is CatRow.Cat -> {
                    val cat = row.cat
                    val isSel = cat.id == selected
                    FocusRow(
                        modifier = Modifier.focusRequester(fr)
                            .flushOnKey(deb, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, scope, fm, FocusDirection.Right),
                        selected = isSel,
                        onFocused = { deb.submit(cat.id) },
                        onClick = { deb.cancel(); onClickCat(cat.id) },
                    ) { f ->
                        Text(if (inGroup) stripPrefix(label(cat)) else label(cat), color = if (f || isSel) C.text else C.sub, fontSize = fontSize.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        count(cat)?.let { Text("$it", color = if (f) C.text else C.sub, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}
