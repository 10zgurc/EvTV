package com.evtv.app

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Ağır işler (büyük liste ayrıştırma, arama dizini, gruplama) için tek, düşük öncelikli iş parçacığı.
 * Zayıf işlemcili cihazlarda (ucuz projektör/TV kutusu) ekran ve kumanda önce cevap versin.
 */
val Heavy = Executors.newSingleThreadExecutor { r ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "evtv-heavy").apply { isDaemon = true }
}.asCoroutineDispatcher()

/** Bu iş parçacığında geçici olarak düşük öncelikle çalış */
inline fun <T> lowPriority(block: () -> T): T {
    val tid = Process.myTid()
    val old = runCatching { Process.getThreadPriority(tid) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
    try {
        return block()
    } finally {
        runCatching { Process.setThreadPriority(old) }
    }
}
