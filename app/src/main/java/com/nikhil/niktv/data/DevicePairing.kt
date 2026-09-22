package com.nikhil.niktv.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Single-use, local-network transfer. The random key is shown only on the receiving device. */
class DevicePairing private constructor(
    private val server: ServerSocket,
    private val key: ByteArray,
    val address: String,
    val code: String
) : AutoCloseable {
    val port: Int get() = server.localPort
    val inviteUri: String get() = Uri.Builder().scheme("niktv").authority("pair")
        .appendQueryParameter("host", address).appendQueryParameter("port", port.toString())
        .appendQueryParameter("key", code).build().toString()

    suspend fun receive(context: Context) = withContext(Dispatchers.IO) {
        try {
            server.soTimeout = 180_000
            server.accept().use { socket ->
                socket.soTimeout = 15_000
                val size = DataInputStream(socket.getInputStream()).readInt()
                require(size in 29..16_384) { "Invalid pairing message." }
                val data = ByteArray(size)
                DataInputStream(socket.getInputStream()).readFully(data)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, data.copyOfRange(0, 12)))
                val configuration = String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
                RemoteCredentials.savePairedConnection(context, configuration)
                DataOutputStream(socket.getOutputStream()).writeBoolean(true)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        runCatching { server.close() }
        key.fill(0)
    }

    companion object {
        fun start(): DevicePairing {
            val address = NetworkInterface.getNetworkInterfaces().toList()
                .asSequence().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: error("Connect both devices to the same local network.")
            val key = ByteArray(16).also(SecureRandom()::nextBytes)
            val code = Base64.encodeToString(key, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            return DevicePairing(ServerSocket(0, 1, InetAddress.getByName(address)), key, address, code)
        }

        suspend fun send(context: Context, address: String, port: Int, code: String) = withContext(Dispatchers.IO) {
            val key = Base64.decode(code.trim(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            require(key.size == 16) { "Enter the complete pairing code." }
            require(port in 1..65535) { "Enter a valid pairing port." }
            require(address.trim().matches(Regex("[0-9.]+"))) { "Enter the new device's local IPv4 address." }
            val host = InetAddress.getByName(address.trim())
            require(host is Inet4Address && host.isSiteLocalAddress) { "Pairing works only on a local network." }
            require(RemoteCredentials.canPairDevices(context)) { "Only a device with a manually entered token can approve pairing." }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                val payload = cipher.iv + cipher.doFinal(RemoteCredentials.pairingConfiguration(context).toByteArray())
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 10_000)
                    socket.soTimeout = 15_000
                    DataOutputStream(socket.getOutputStream()).apply { writeInt(payload.size); write(payload); flush() }
                    check(DataInputStream(socket.getInputStream()).readBoolean()) { "The receiving device did not accept pairing." }
                }
            } finally {
                key.fill(0)
            }
        }
    }
}

data class PairingInvite(val address: String = "", val port: String = "", val code: String = "",
    val remote: RemotePairingSession? = null)

object PairingInvites {
    var pending by mutableStateOf<PairingInvite?>(null)
        private set

    fun accept(uri: Uri?) {
        if (uri?.scheme != "niktv" || uri.host != "pair") return
        if (uri.getQueryParameter("relay") != null) {
            runCatching { RemotePairing.parseInvite(uri.toString()) }
                .onSuccess { pending = PairingInvite(remote = it) }
            return
        }
        val address = uri.getQueryParameter("host").orEmpty()
        val port = uri.getQueryParameter("port").orEmpty()
        val code = uri.getQueryParameter("key").orEmpty()
        if (address.matches(Regex("[0-9.]+")) && port.toIntOrNull() in 1..65535 && code.isNotBlank()) {
            pending = PairingInvite(address, port, code)
        }
    }

    fun clear() { pending = null }
}
