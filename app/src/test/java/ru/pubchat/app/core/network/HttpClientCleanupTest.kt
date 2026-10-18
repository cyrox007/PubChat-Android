package ru.pubchat.app.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class HttpClientCleanupTest {
    @Test
    fun pooledSocketIsClosedOffCallerThreadAndDispatcherIsReleased() {
        val caller = Thread.currentThread()
        val closedBy = AtomicReference<Thread>()
        val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun close() {
                    closedBy.set(Thread.currentThread())
                    super.close()
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unused overload")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unused overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unused overload")
        }
        val serverExecutor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().socketFactory(socketFactory).build()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val served = serverExecutor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume request */ }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                            flush()
                        }
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                    .execute().use { assertEquals("OK", it.body!!.string()) }
                assertEquals(1, client.connectionPool.idleConnectionCount())

                HttpClientCleanup.release(client).get(5, TimeUnit.SECONDS)

                assertNotNull(closedBy.get())
                assertNotSame(caller, closedBy.get())
                assertEquals(0, client.connectionPool.connectionCount())
                assertTrue(client.dispatcher.executorService.isShutdown)
                // Repeated lifecycle cleanup must remain safe.
                HttpClientCleanup.release(client).get(5, TimeUnit.SECONDS)
                served.get(5, TimeUnit.SECONDS)
            }
        } finally {
            serverExecutor.shutdownNow()
            HttpClientCleanup.release(client).get(5, TimeUnit.SECONDS)
        }
    }
}
