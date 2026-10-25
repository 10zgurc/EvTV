package com.evtv.app

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Ne kadar video önceden yüklensin. Yüksek değer = başlarken biraz daha bekleme,
 * ama internet/sunucu anlık yavaşladığında daha az donma.
 */
@OptIn(UnstableApi::class)
fun buildLoadControl(level: Int, live: Boolean): LoadControl {
    // (başlamak için gereken, donmadan sonra devam için gereken) saniye
    val (start, afterRebuffer) = when (level) {
        0 -> 2_500 to 5_000
        2 -> 8_000 to 20_000
        else -> 4_000 to 10_000
    }
    val minBuf = if (live) 20_000 else 40_000
    val maxBuf = if (live) 60_000 else 120_000
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(maxOf(minBuf, afterRebuffer), maxBuf, start, afterRebuffer)
        // Bellek sınırı (≈130 MB) aşılmasın: 4K filmde bile TV çökmesin
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

fun bufferLevelName(level: Int) = when (level) {
    0 -> "Normal (hızlı açılış)"
    2 -> "Çok yüksek (en az donma)"
    else -> "Yüksek"
}
