package com.dispatch.driver.status

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

internal class UuidV7Generator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(epochMillis: Long): String {
        require(epochMillis >= 0) { "UUIDv7 timestamp must be non-negative" }
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        for (index in 0 until 6) {
            val shift = (5 - index) * 8
            bytes[index] = ((epochMillis ushr shift) and 0xff).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
