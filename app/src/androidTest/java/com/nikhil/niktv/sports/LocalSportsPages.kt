package com.nikhil.niktv.sports

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors

internal class LocalSportsPages(private val pages: Map<String, String>) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "SportsPageFixture").apply { isDaemon = true } }
    val base = "http://127.0.0.1:${server.localPort}"
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                workers.execute {
                    socket.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: "/"
                        while (!input.readLine().isNullOrEmpty()) { }
                        val body = pages[path].orEmpty().toByteArray(Charsets.UTF_8)
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
