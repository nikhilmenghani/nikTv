package com.nikhil.niktv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decode pre-release identity requests only, so older senders receive an explicit update message. */
@Serializable
data class RemoteMovie(val portal: String, val id: String, val title: String, val categoryId: String? = null) {
    fun validate() {
        require(portal.matches(Regex("[a-f0-9]{64}")) && id.length in 1..512 && title.length in 1..512)
        require(categoryId == null || categoryId.length in 1..128 && categoryId.matches(Regex("[\\p{L}\\p{N}_*.-]+")))
        require("://" !in id && '?' !in id && '@' !in id) { "A remote movie must have a provider ID." }
    }
}

@Serializable
data class RemoteVideo(val url: String, val title: String, val format: String = "",
    val headers: Map<String, String> = emptyMap(), val positionMillis: Long = 0, val portal: String? = null) {
    fun validate() {
        val uri = java.net.URI(url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && url.length <= 4096)
        require(title.length in 1..200 && format.length <= 80 && positionMillis >= 0)
        require(portal == null || portal.matches(Regex("[a-f0-9]{64}")))
        require(headers.size <= 24 && headers.entries.sumOf { it.key.length + it.value.length } <= 8192)
        require(headers.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]{1,128}")) && '\r' !in value && '\n' !in value })
    }
}

@Serializable
data class LanCommand(val action: String, val credential: String, val movie: RemoteMovie? = null, val video: RemoteVideo? = null,
    val sender: String = "", val senderId: String = "", val key: String? = null, val text: String? = null)

@Serializable
data class LanReply(val ok: Boolean, val message: String, val credential: String? = null,
    val videoReady: Boolean = false, val positionMillis: Long = 0, val hasPlayback: Boolean? = null)

data class LanTarget(val name: String, val host: String, val port: Int, val credential: String)

fun remotePortalIdentity(type: String, url: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$type|${url.trim().trimEnd('/')}".toByteArray()).joinToString("") { "%02x".format(it) }

/** Ephemeral ECDH + authenticated encryption keeps PINs and control tokens off the wire in clear text. */
internal object LanWire {
    val json = Json { ignoreUnknownKeys = false }
    private fun read(input: DataInputStream, max: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..max) { "Invalid remote message size" }
        return ByteArray(size).also(input::readFully)
    }
    private fun write(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size); output.write(value); output.flush()
    }
    fun exchange(socket: Socket, server: Boolean, reply: ((LanCommand) -> LanReply)? = null,
        command: LanCommand? = null): LanReply {
        socket.soTimeout = if (server) 5_000 else 120_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        write(output, pair.public.encoded)
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(read(input, 256)))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(peer, true) }.generateSecret()
        // Distinct directions prevent reflection of a request as a response.
        fun key(direction: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest(shared + direction.toByteArray()), "AES")
        fun send(text: String, direction: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(direction))
            write(output, cipher.iv + cipher.doFinal(text.toByteArray()))
        }
        fun receive(direction: String): String {
            val bytes = read(input, 16_384)
            require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(direction), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
        try {
            return if (server) {
                val response = requireNotNull(reply)(json.decodeFromString<LanCommand>(receive("request")))
                send(json.encodeToString(response), "reply")
                response
            } else {
                send(json.encodeToString(requireNotNull(command)), "request")
                json.decodeFromString<LanReply>(receive("reply"))
            }
        } finally { shared.fill(0) }
    }
}

/** Explicitly enabled, foreground-only receiver. Bounded workers and pairing attempt limit. */
class LanPlaybackServer(address: InetAddress, preferredPort: Int = 0, private val execute: (LanCommand) -> LanReply) : AutoCloseable {
    private val sessions = LanReceiverSessions()
    val pin: String get() = sessions.snapshot().pin
    val snapshot: LanReceiverSnapshot get() = sessions.snapshot()
    fun observePlayback(active: Boolean) = sessions.observePlayback(active)
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, preferredPort), 4)
    }
    val port: Int get() = socket.localPort
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    init {
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clients.add(client)
                try { workers.execute {
                    try { client.use { LanWire.exchange(it, server = true, reply = ::accept) } }
                    catch (_: Exception) { /* Bad clients cannot stop discovery or the listener. */ }
                    finally { clients.remove(client) }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            }
        }, "NikTV-LAN").apply { isDaemon = true; start() }
    }
    private fun accept(command: LanCommand): LanReply {
        if (command.action == "pair") return sessions.pair(command.credential, command.sender, command.senderId)
        if (!sessions.authenticated(command.credential)) return LanReply(false, "Pair again on the receiving device.")
        if (command.action !in setOf("play", "pause", "resume", "stop", "status", "navigate", "text", "clear_text")) return LanReply(false, "Unsupported command")
        if (command.action == "navigate" && command.key !in setOf("up", "down", "left", "right", "ok", "back"))
            return LanReply(false, "Invalid navigation key")
        if (command.action == "text" && (command.text == null || command.text.length > 1024 || '\u0000' in command.text))
            return LanReply(false, "Invalid text request")
        if (command.action == "play" && command.movie != null)
            return LanReply(false, "Update both devices. The sender must send the selected movie's playback link.")
        if (command.action == "play") runCatching {
            requireNotNull(command.video).validate()
        }
            .onFailure { return LanReply(false, "Invalid movie request") }
        if (command.action == "status") return execute(command)
        val (permit, error) = sessions.begin(command.credential, command.action)
        if (permit == null) return LanReply(false, requireNotNull(error))
        var ok = false
        try { return execute(command).also { ok = it.ok } }
        finally { sessions.finish(permit, ok) }
    }
    fun removeSender(id: String): LanReply {
        val (permit, error) = sessions.beginRemoval(id)
        if (permit == null) return LanReply(false, requireNotNull(error))
        var ok = false
        try {
            val reply = if (permit.previousOwner == permit.sender) execute(LanCommand("stop", "")) else LanReply(true, "Disconnected")
            ok = reply.ok
            return reply.copy(message = if (ok) "Device disconnected" else reply.message)
        } finally { sessions.finishRemoval(permit, ok) }
    }
    override fun close() {
        socket.close()
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
        workers.shutdownNow()
        sessions.clear()
    }
}

fun sendLanCommand(host: String, port: Int, command: LanCommand): LanReply {
    require(port in 1..65535)
    val address = InetAddress.getByName(host)
    require(address.isSiteLocalAddress || address.isLoopbackAddress) { "Choose a device on your local network." }
    return Socket().use {
        it.connect(InetSocketAddress(address, port), 5_000)
        LanWire.exchange(it, server = false, command = command)
    }
}
