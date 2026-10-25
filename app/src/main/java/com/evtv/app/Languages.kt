package com.evtv.app

/** Altyazı dilleri (OpenSubtitles kodları). */
val SUB_LANGS: List<Pair<String, String>> = listOf(
    "tr" to "Türkçe",
    "en" to "İngilizce",
    "de" to "Almanca",
    "fr" to "Fransızca",
    "es" to "İspanyolca",
    "it" to "İtalyanca",
    "pt-PT" to "Portekizce",
    "pt-BR" to "Portekizce (Brezilya)",
    "nl" to "Felemenkçe",
    "pl" to "Lehçe",
    "ru" to "Rusça",
    "uk" to "Ukraynaca",
    "ar" to "Arapça",
    "fa" to "Farsça",
    "ku" to "Kürtçe",
    "az" to "Azerice",
    "el" to "Yunanca",
    "bg" to "Bulgarca",
    "ro" to "Romence",
    "hu" to "Macarca",
    "cs" to "Çekçe",
    "sk" to "Slovakça",
    "sv" to "İsveççe",
    "da" to "Danca",
    "no" to "Norveççe",
    "fi" to "Fince",
    "sq" to "Arnavutça",
    "bs" to "Boşnakça",
    "hr" to "Hırvatça",
    "sr" to "Sırpça",
    "he" to "İbranice",
    "hi" to "Hintçe",
    "ja" to "Japonca",
    "ko" to "Korece",
    "zh-CN" to "Çince (Basitleştirilmiş)",
    "zh-TW" to "Çince (Geleneksel)",
)

fun langName(code: String): String =
    SUB_LANGS.firstOrNull { it.first.equals(code, true) }?.second
        ?: if (code == "off") "Kapalı" else code.uppercase()

/** Videodaki altyazı izinin dili (örn. "tr", "tur", "pt-br") istenen dille aynı mı? */
fun sameLang(trackLang: String?, wanted: String): Boolean {
    val t = trackLang?.lowercase()?.replace('_', '-') ?: return false
    val w = wanted.lowercase()
    if (t == w) return true
    val tBase = t.substringBefore('-')
    val wBase = w.substringBefore('-')
    // Bölge belirtilmemişse (örn. "pt") ana dil eşleşmesi yeterli
    if (tBase == wBase && (!w.contains('-') || !t.contains('-'))) return true
    val iso3 = mapOf(
        "tur" to "tr", "eng" to "en", "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr",
        "spa" to "es", "ita" to "it", "por" to "pt", "dut" to "nl", "nld" to "nl", "pol" to "pl",
        "rus" to "ru", "ukr" to "uk", "ara" to "ar", "per" to "fa", "fas" to "fa", "kur" to "ku",
        "aze" to "az", "gre" to "el", "ell" to "el", "bul" to "bg", "rum" to "ro", "ron" to "ro",
        "hun" to "hu", "cze" to "cs", "ces" to "cs", "slo" to "sk", "slk" to "sk", "swe" to "sv",
        "dan" to "da", "nor" to "no", "fin" to "fi", "alb" to "sq", "sqi" to "sq", "bos" to "bs",
        "hrv" to "hr", "srp" to "sr", "heb" to "he", "hin" to "hi", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh",
    )
    return (iso3[tBase] ?: tBase) == wBase
}
