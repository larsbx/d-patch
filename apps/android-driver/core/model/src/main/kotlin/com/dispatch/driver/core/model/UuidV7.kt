package com.dispatch.driver.core.model

import java.security.SecureRandom
import java.util.UUID

/**
 * RFC 9562 UUIDv7: a 48-bit Unix-millisecond prefix, then random bits.
 *
 * Section 24.1 has the client assign `event_id` as a UUIDv7, so an offline
 * declaration has its permanent identifier before it ever reaches the server
 * and the device can reconcile by the ID it recorded. The time prefix keeps
 * IDs from one device in creation order, which is also the order they queue.
 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(nowMillis: Long = System.currentTimeMillis()): UUID {
        val randomBits = ByteArray(10).also(random::nextBytes)
        val randA = ((randomBits[0].toLong() and 0xFF) shl 8 or (randomBits[1].toLong() and 0xFF)) and 0x0FFF
        val msb = (nowMillis and 0xFFFF_FFFF_FFFFL) shl 16 or (0x7L shl 12) or randA
        val lsb = randomBits.copyOfRange(2, 10).fold(0L) { acc, b -> acc shl 8 or (b.toLong() and 0xFF) }
            .let { (it and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE }
        return UUID(msb, lsb)
    }
}
