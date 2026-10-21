package com.dispatch.driver.core.model.status

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** CoopEventCanonicalV1 subset: text-keyed maps, text, integers and null.
 * Map order is unsigned lexicographic order of the ENCODED keys, as in the
 * platform Rust NIF. No generic serializer's map ordering is trusted here.
 */
object StatusSigningBytes {
    fun encode(declaration: StatusDeclaration, roleAssignmentId: String): ByteArray = encodeTerm(
        buildJsonObject {
            put("schema", "DispatchStatusV1")
            put("role_assignment_id", roleAssignmentId)
            put("payload", Json.parseToJsonElement(declaration.copy(deviceSignature = null).toCanonicalJson()))
        },
    )

    private fun encodeTerm(value: JsonElement): ByteArray {
        val out = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            require(n >= 0)
            when {
                n < 24 -> out.write((major shl 5) or n.toInt())
                else -> {
                    val count = when { n <= 255 -> 1; n <= 65535 -> 2; n <= 0xffffffffL -> 4; else -> 8 }
                    out.write((major shl 5) or when(count) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 })
                    for (i in count - 1 downTo 0) out.write((n ushr (i * 8)).toInt() and 255)
                }
            }
        }
        when (value) {
            is JsonObject -> {
                val entries = value.entries.map { encodeTerm(JsonPrimitive(it.key)) to encodeTerm(it.value) }
                    .sortedWith { x, y -> compareUnsigned(x.first, y.first) }
                head(5, entries.size.toLong())
                entries.forEach { out.write(it.first); out.write(it.second) }
            }
            JsonNull -> out.write(246)
            is JsonPrimitive -> if (value.isString) {
                val bytes = value.content.toByteArray(Charsets.UTF_8)
                head(3, bytes.size.toLong()); out.write(bytes)
            } else {
                val n = value.longOrNull ?: error("Non-integral signed payload")
                if (n >= 0) head(0, n) else head(1, -(n + 1))
            }
            else -> error("Unsupported signed payload")
        }
        return out.toByteArray()
    }
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val cmp = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }
}
