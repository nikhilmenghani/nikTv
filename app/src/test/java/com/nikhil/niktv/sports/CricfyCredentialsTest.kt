package com.nikhil.niktv.sports

import com.nikhil.niktv.data.RemoteCredentials
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class CricfyCredentialsTest {
    private val key = "fixture-key-1234"
    private val iv = "fixture-iv-12345"
    private fun encrypted(): String = Base64.getEncoder().encodeToString(
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(this@CricfyCredentialsTest.iv.toByteArray()))
            doFinal("[]".toByteArray())
        })

    @Test fun bothPairsCanBeLoadedFromEnvAndUsedAsLiteralUtf8() {
        for (index in 1..2) {
            val values = RemoteCredentials.parseKnownValues("NIKTV_CRICFY_AES_KEY_$index='$key'\nNIKTV_CRICFY_AES_IV_$index='$iv'")
            assertEquals(2, values.size)
            assertEquals("[]", SportsCatalogue.document(encrypted()) { values[it].orEmpty() }.toString())
        }
    }

    @Test fun absentInvalidAndWrongKeysFailWithoutExposingValues() {
        for (values in listOf(emptyMap(), mapOf("NIKTV_CRICFY_AES_KEY_1" to "short"),
            mapOf("NIKTV_CRICFY_AES_KEY_1" to "wrong-key-1234567", "NIKTV_CRICFY_AES_IV_1" to iv))) {
            try { SportsCatalogue.document(encrypted()) { values[it].orEmpty() }; fail("Must reject unavailable keys") }
            catch (error: SportsException) { assertFalse(error.message.orEmpty().contains(key)); assertFalse(error.message.orEmpty().contains(iv)) }
        }
        assertEquals("[]", SportsCatalogue.document("[]") { error("Plain JSON needs no credentials") }.toString())
    }
}
