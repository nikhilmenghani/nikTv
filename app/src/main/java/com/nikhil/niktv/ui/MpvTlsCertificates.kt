package com.nikhil.niktv.ui

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyStore

/** Native TLS uses Android's trusted certificates, never a verification bypass. */
internal fun mpvTlsCertificateFile(context: Context): File {
    val directory = File(context.filesDir, "mpv").apply { mkdirs() }
    val target = File(directory, "android-ca.pem")
    if (target.exists() && System.currentTimeMillis() - target.lastModified() < 86_400_000L) return target
    val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val pem = buildString {
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val certificate = store.getCertificate(aliases.nextElement()) ?: continue
            append("-----BEGIN CERTIFICATE-----\n")
            append(Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            append("\n-----END CERTIFICATE-----\n")
        }
    }
    check(pem.isNotEmpty()) { "Android certificate store is empty" }
    val temporary = File(directory, "android-ca.tmp")
    temporary.writeText(pem)
    check(temporary.renameTo(target)) { "Could not prepare native TLS certificates" }
    return target
}
