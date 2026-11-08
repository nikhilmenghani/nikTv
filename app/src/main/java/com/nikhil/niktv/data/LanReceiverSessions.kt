package com.nikhil.niktv.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class LanPairedDevice(val id: String, val name: String, val ownsPlayback: Boolean)
data class LanReceiverSnapshot(val pin: String, val secondsRemaining: Int, val devices: List<LanPairedDevice>)

/** Short synchronized state transitions; player/network work always runs outside this lock. */
internal class LanReceiverSessions(private val now: () -> Long = System::nanoTime) {
    private data class Sender(val id: String, val name: String, val installation: String, val token: String)
    internal data class Permit(val sender: String, val action: String, val previousOwner: String?)
    private val random = SecureRandom()
    private val senders = mutableListOf<Sender>()
    private var code = ""
    private var expires = 0L
    private var failures = 0
    private var owner: String? = null
    private var pending: Permit? = null
    private fun secret() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    private fun rotate() {
        if (code.isNotEmpty() && now() < expires) return
        var next: String
        do { next = "%06d".format(java.util.Locale.US, random.nextInt(1_000_000)) } while (next == code)
        code = next; expires = now() + TimeUnit.MINUTES.toNanos(5); failures = 0
    }
    @Synchronized fun snapshot(): LanReceiverSnapshot {
        rotate()
        val remaining = TimeUnit.NANOSECONDS.toSeconds((expires - now()).coerceAtLeast(0)) + 1
        return LanReceiverSnapshot(code, remaining.coerceAtMost(300).toInt(), senders.map { LanPairedDevice(it.id, it.name, owner == it.id) })
    }
    @Synchronized fun pair(pin: String, name: String, installation: String): LanReply {
        rotate()
        if (failures >= 5 || !equal(pin, code)) {
            failures++
            return LanReply(false, "Pairing failed. Check the current code on the receiving device.")
        }
        val identity = installation.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }.orEmpty()
        val old = senders.firstOrNull { identity.isNotEmpty() && it.installation == identity }
        if (old == null && senders.size >= 8) return LanReply(false, "Remove an unused paired device on the receiver first.")
        if (pending?.sender == old?.id && old != null) return LanReply(false, "A playback command is finishing. Try again shortly.")
        val sender = Sender(old?.id ?: java.util.UUID.randomUUID().toString(),
            name.filterNot(Char::isISOControl).trim().take(64).ifBlank { "NikTV device" }, identity, secret())
        senders.removeAll { it.id == sender.id }; senders += sender
        return LanReply(true, "Connected", sender.token)
    }
    @Synchronized fun authenticated(token: String): Boolean = senders.any { equal(it.token, token) }
    @Synchronized fun begin(token: String, action: String): Pair<Permit?, String?> {
        val sender = senders.firstOrNull { equal(it.token, token) } ?: return null to "Pair again on the receiving device."
        if (pending != null) return null to "A playback command is finishing. Try again shortly."
        if (owner != null && owner != sender.id) {
            val name = senders.firstOrNull { it.id == owner }?.name ?: "another device"
            return null to "Playback is controlled by $name. Stop it or disconnect that device on the receiver first."
        }
        val permit = Permit(sender.id, action, owner)
        pending = permit
        if (action == "play") owner = sender.id
        return permit to null
    }
    @Synchronized fun finish(permit: Permit, ok: Boolean) {
        if (pending != permit) return
        if (ok && permit.action == "stop") owner = null
        if (!ok && permit.action == "play") owner = permit.previousOwner
        pending = null
    }
    @Synchronized fun observePlayback(active: Boolean) { if (!active && pending == null) owner = null }
    @Synchronized fun beginRemoval(id: String): Pair<Permit?, String?> {
        val sender = senders.firstOrNull { it.id == id } ?: return null to "Device is no longer paired."
        if (pending != null) return null to "A playback command is finishing. Try again shortly."
        val permit = Permit(sender.id, "remove", owner)
        pending = permit
        return permit to null
    }
    @Synchronized fun finishRemoval(permit: Permit, ok: Boolean) {
        if (pending != permit) return
        if (ok) { senders.removeAll { it.id == permit.sender }; if (owner == permit.sender) owner = null }
        pending = null
    }
    @Synchronized fun clear() { senders.clear(); owner = null; pending = null }
}
