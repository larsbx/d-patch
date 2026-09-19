package com.dispatch.driver.core.model

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
    private val secureRandom = SecureRandom()

    fun generate(
        unixMillis: Long = System.currentTimeMillis(),
        random: SecureRandom = secureRandom,
    ): UUID {
        require(unixMillis in 0..TIMESTAMP_MASK) { "UUIDv7 timestamp must fit in 48 bits." }

        val mostSignificantBits =
            ((unixMillis and TIMESTAMP_MASK) shl 16) or
                0x7000L or
                random.nextInt(0x1000).toLong()
        val leastSignificantBits =
            (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE

        return UUID(mostSignificantBits, leastSignificantBits)
    }
}
