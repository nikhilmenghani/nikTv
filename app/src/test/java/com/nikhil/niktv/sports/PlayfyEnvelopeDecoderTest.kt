package com.nikhil.niktv.sports

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol documents only: no provider credentials or captured stream URLs. */
class PlayfyEnvelopeDecoderTest {
    private val json = Json
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (31 - it).toByte() }
    private val marker = Base64.getDecoder().decode("OM3iiPBg+o9HaraBH/IVdnw1EXv2JI/YrRV81FZomMw=")
    private val content = """[{"id":"fixture","title":"Synthetic catalogue"}]"""

    private fun credentials(name: String): String = when (name) {
        "NIKTV_PLAYFY_AES_KEY" -> Base64.getEncoder().encodeToString(key)
        "NIKTV_PLAYFY_AES_IV" -> Base64.getEncoder().encodeToString(iv)
        else -> ""
    }

    private fun wrapper(bytes: ByteArray, code: String? = null): String = buildJsonObject {
        put("data", Base64.getEncoder().encodeToString(bytes))
        code?.let { put("code", it) }
    }.toString()

    private fun aes(data: ByteArray, secret: ByteArray = key, vector: ByteArray = iv): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(vector)); doFinal(data)
        }

    private fun legacy(data: ByteArray, pkcs7: Boolean = true): ByteArray {
        val count = 16 - data.size % 16
        val padded = data + ByteArray(count) { if (pkcs7) count.toByte() else 0 }
        return aes(padded)
    }

    private fun primary(data: ByteArray, declaredLength: Int = data.size): ByteArray {
        val nonce = ByteArray(32) { (it * 3 + 7).toByte() }
        val secret = MessageDigest.getInstance("SHA-256").digest(nonce)
        fun hmac(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256")); doFinal(message)
        }
        val primaryKey = hmac(byteArrayOf(1))
        val primaryIv = hmac(primaryKey + byteArrayOf(2)).copyOf(16)
        val padded = ByteBuffer.allocate(((data.size + 4 + 15) / 16) * 16).putInt(declaredLength)
        data.forEachIndexed { index, byte ->
            val masked = (byte.toInt() xor marker[index % marker.size].toInt()) and 255
            padded.put(((masked shl 3) or (masked ushr 5)).toByte())
        }
        val ciphertext = aes(padded.array(), primaryKey, primaryIv)
        return ByteBuffer.allocate(4 + 4 + marker.size + 4 + nonce.size + ciphertext.size)
            .putInt(0xDEADBEEF.toInt()).putInt(marker.size).put(marker)
            .putInt(nonce.size).put(nonce).put(ciphertext).array()
    }

    @Test fun legacyAesCatalogueAcceptsValidPkcs7AndZeroTerminatedDocuments() {
        val expected = json.parseToJsonElement(content)
        for (pkcs7 in listOf(true, false)) {
            assertEquals(expected, PlayfyEnvelopeDecoder.document(wrapper(legacy(content.toByteArray(), pkcs7)), ::credentials))
        }
    }

    @Test fun primaryEnvelopeDecodesWithoutPrivateLegacyMaterial() {
        assertEquals(json.parseToJsonElement(content), PlayfyEnvelopeDecoder.document(wrapper(primary(content.toByteArray()))) { "" })
    }

    @Test fun truncatedAndOversizedEnvelopeLengthsAreRejected() {
        val bytes = primary(content.toByteArray())
        for (length in listOf(0, 3, 8, 39, 43, bytes.size - 1)) {
            assertThrows("Truncated envelope at $length", SportsException::class.java) {
                PlayfyEnvelopeDecoder.document(wrapper(bytes.copyOf(length))) { "" }
            }
        }
        val hugeNonce = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(40, Int.MAX_VALUE) }
        val hugeMarker = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) }
        val hugePlaintext = primary(content.toByteArray(), Int.MAX_VALUE)
        val negativePlaintext = primary(content.toByteArray(), -1)
        for (malformed in listOf(hugeNonce, hugeMarker, hugePlaintext, negativePlaintext)) {
            assertThrows(SportsException::class.java) { PlayfyEnvelopeDecoder.document(wrapper(malformed)) { "" } }
        }
    }

    @Test fun invalidUtf8IsRejectedInBothEnvelopeFormats() {
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows(SportsException::class.java) { PlayfyEnvelopeDecoder.document(wrapper(primary(invalid))) { "" } }
        assertThrows(SportsException::class.java) { PlayfyEnvelopeDecoder.document(wrapper(legacy(invalid)), ::credentials) }
    }

    @Test fun wrongAndMissingAesConfigurationCannotProduceCatalogueData() {
        val body = wrapper(legacy(content.toByteArray()))
        assertThrows(SportsException::class.java) { PlayfyEnvelopeDecoder.document(body) { "" } }
        assertThrows(SportsException::class.java) {
            PlayfyEnvelopeDecoder.document(body) { name ->
                if (name == "NIKTV_PLAYFY_AES_KEY") Base64.getEncoder().encodeToString(ByteArray(16) { 99 }) else credentials(name)
            }
        }
    }

    @Test fun serviceErrorCodeIsRejectedBeforeAttemptingDecryption() {
        assertThrows(SportsException::class.java) {
            PlayfyEnvelopeDecoder.document(wrapper(primary(content.toByteArray()), code = "403")) {
                fail("Error response must not request private decryption material"); ""
            }
        }
    }

    @Test fun plainStructuredJsonAndSuccessfulCodeAreAccepted() {
        assertEquals(json.parseToJsonElement(content), PlayfyEnvelopeDecoder.document(content) { "" })
        val body = wrapper(primary(content.toByteArray()), code = "0")
        assertEquals(json.parseToJsonElement(content), PlayfyEnvelopeDecoder.document(body) { "" })
    }
}
