package com.nikhil.niktv.sports

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

/** Independent decoder for the observed Playfy JSON transport envelopes. */
internal object PlayfyEnvelopeDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_BYTES = 4 * 1024 * 1024
    // Protocol identifier carried in the envelope, not an account or decryption key.
    private val envelopeMarker = Base64.getDecoder().decode("OM3iiPBg+o9HaraBH/IVdnw1EXv2JI/YrRV81FZomMw=")

    fun document(body: String, credential: (String) -> String): JsonElement {
        if (body.length > MAX_BYTES * 2) throw SportsException("The Playfy response is too large.")
        val root = runCatching { json.parseToJsonElement(body.trim().removePrefix("\uFEFF")) }.getOrNull()
            ?: throw SportsException("The Playfy service returned an unreadable response.")
        val wrapper = root as? JsonObject ?: return structured(root)
        val encoded = (wrapper["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return structured(root)
        val code = (wrapper["code"] as? JsonPrimitive)?.contentOrNull
        if (code != null && code != "0") throw SportsException("The Playfy service could not provide this catalogue.")
        val bytes = runCatching { base64(encoded) }.getOrNull()
            ?: throw SportsException("The Playfy service returned an unreadable response.")
        runCatching { structured(json.parseToJsonElement(utf8(decodeEnvelope(bytes)))) }.getOrNull()?.let { return it }
        val key = runCatching { base64(credential("NIKTV_PLAYFY_AES_KEY")) }.getOrNull()
        val iv = runCatching { base64(credential("NIKTV_PLAYFY_AES_IV")) }.getOrNull()
        if (key == null || key.size !in setOf(16, 24, 32) || iv == null || iv.size != 16)
            throw SportsException("Playfy decryption configuration is missing. Sync private configuration in Settings, then retry.")
        return runCatching { structured(json.parseToJsonElement(utf8(unpad(aes(bytes, key, iv))))) }.getOrElse {
            throw SportsException("The Playfy response could not be decoded. Refresh private configuration and retry.", it)
        }
    }

    private fun structured(value: JsonElement): JsonElement {
        if (value !is JsonArray && value !is JsonObject) throw SportsException("The Playfy service returned an unreadable response.")
        return value
    }

    private fun base64(value: String): ByteArray {
        require(value.length <= MAX_BYTES * 2)
        return Base64.getDecoder().decode(value.filterNot(Char::isWhitespace)).also { require(it.size <= MAX_BYTES) }
    }

    private fun decodeEnvelope(bytes: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(bytes)
        require(input.remaining() >= 16 && input.int == 0xDEADBEEF.toInt())
        val markerLength = input.int
        require(markerLength == envelopeMarker.size && input.remaining() >= markerLength + 4)
        val marker = ByteArray(markerLength).also(input::get)
        require(MessageDigest.isEqual(marker, envelopeMarker))
        val nonceLength = input.int
        require(nonceLength > 0 && nonceLength <= input.remaining() - 16)
        val nonce = ByteArray(nonceLength).also(input::get)
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        val secret = MessageDigest.getInstance("SHA-256").digest(nonce)
        fun expand(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256")); doFinal(message)
        }
        val key = expand(byteArrayOf(1))
        val iv = expand(key + byteArrayOf(2)).copyOf(16)
        val padded = aes(ciphertext, key, iv)
        require(padded.size >= 4)
        val length = ByteBuffer.wrap(padded).int
        require(length > 0 && length <= padded.size - 4)
        return ByteArray(length) { index ->
            val value = padded[index + 4].toInt() and 255
            (((value ushr 3) or ((value and 7) shl 5)) xor (marker[index % marker.size].toInt() and 255)).toByte()
        }
    }

    private fun aes(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0)
        return Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(ciphertext)
        }
    }

    private fun unpad(bytes: ByteArray): ByteArray {
        var length = bytes.size
        if (length > 0) {
            val pad = bytes.last().toInt() and 255
            if (pad in 1..16 && pad <= length && (length - pad until length).all { (bytes[it].toInt() and 255) == pad }) length -= pad
        }
        val zero = bytes.indexOf(0)
        if (zero >= 0 && zero < length) length = zero
        return bytes.copyOf(length)
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
