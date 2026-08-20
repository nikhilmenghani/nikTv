package com.nikhil.niktv.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/** Deterministic legacy device identity used by Cast4K before any server-side registration check. */
data class Cast4kDeviceIdentity(val macAddress: String, val serialNumber: String)

fun cast4kLegacyDeviceIdentity(context: Context): Cast4kDeviceIdentity {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val md5 = BigInteger(1, MessageDigest.getInstance("MD5").digest(androidId.toByteArray()))
        .toString(16).padStart(32, '0')
    val mac = "00:1E:99:${md5.substring(0, 2)}:${md5.substring(2, 4)}:${md5.substring(4, 6)}"
        .uppercase(Locale.ROOT)
    val serialPrefix = when (Build.VERSION.SDK_INT) {
        24 -> "032016J0"
        25 -> "022017J0"
        26 -> "012018J0"
        27 -> "022018J0"
        28 -> "032019J0"
        29 -> "042020J0"
        30 -> "052021J0"
        31 -> "062021J0"
        32 -> "072022J0"
        33 -> "082023J0"
        34 -> "092024J0"
        35 -> "102025J0"
        else -> "999999J0"
    }
    val decimalSuffix = runCatching { BigInteger(androidId, 16).toString(10).take(5) }
        .getOrDefault("00000")
    return Cast4kDeviceIdentity(mac, serialPrefix + decimalSuffix)
}
