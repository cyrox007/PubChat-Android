package ru.pubchat.app.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** TLS socket close can write to the network, so pool eviction must not run on the UI thread. */
internal object HttpClientCleanup {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PubChat-http-cleanup").apply { isDaemon = true }
    }

    fun release(client: OkHttpClient): Future<*> = executor.submit {
        try {
            client.connectionPool.evictAll()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
