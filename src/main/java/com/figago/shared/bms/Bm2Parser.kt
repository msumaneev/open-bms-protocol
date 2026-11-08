package com.figago.shared.bms

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class Bm2Parser {

    private val secretKey = byteArrayOf(
        0x6c, 0x65, 0x77, 0x65, 0x69, 0x5f, 0x42, 0x4c,
        0x45, 0x5f, 0x74, 0x6f, 0x5f, 0x61, 0x70, 0x70
    )

    private val cipher: Cipher by lazy {
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"))
        }
    }

    fun parseManufacturerData(bytes: ByteArray): Float? {
        if (bytes.size < 16) return null
        return try {
            val decrypted = cipher.doFinal(bytes.copyOfRange(0, 16))
            val v1 = decrypted[1].toInt() and 0xFF
            val v2 = decrypted[2].toInt() and 0xFF
            ((v1 shl 8) or v2) / 100f
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun calculateSoc12V(v: Float): Int {
            return when {
                v >= 12.8f -> 100
                v >= 12.6f -> 90
                v >= 12.5f -> 80
                v >= 12.4f -> 70
                v >= 12.3f -> 60
                v >= 12.2f -> 50
                v >= 12.1f -> 40
                v >= 11.9f -> 30
                v >= 11.8f -> 20
                v >= 11.6f -> 10
                else -> 0
            }
        }

        fun calculateSoc24V(v: Float): Int {
            return when {
                v >= 25.6f -> 100
                v >= 25.2f -> 90
                v >= 25.0f -> 80
                v >= 24.8f -> 70
                v >= 24.6f -> 60
                v >= 24.4f -> 50
                v >= 24.2f -> 40
                v >= 23.8f -> 30
                v >= 23.6f -> 20
                v >= 23.2f -> 10
                else -> 0
            }
        }
    }
}
