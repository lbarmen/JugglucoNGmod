package mod.pumpbt

import tk.glucodata.mod.pumpbt.core.hexToBytes
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Hex to bytes for test vectors. */
internal fun hex(s: String): ByteArray = hexToBytes(s)!!

/**
 * A made-up SAKE key database (format of OpenMinimed's key_databases.md): CRC-32 (big endian) of the rest, local
 * device type, number of entries, then per entry the remote type and 80 bytes of keys. The key bytes are a simple
 * pattern from [seed] - nothing real.
 */
internal fun fakeKeyDatabase(local: Int, remotes: List<Int>, seed: Int = 1): ByteArray {
    val body = ByteArrayOutputStream()
    body.write(local)
    body.write(remotes.size)
    for ((i, type) in remotes.withIndex()) {
        body.write(type)
        repeat(80) { j -> body.write((seed * 31 + i * 7 + j * 13) and 0xFF) }
    }
    val b = body.toByteArray()
    val crc = CRC32().apply { update(b) }.value
    return byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()) + b
}

/** Hex in groups of two separated by spaces, broken into lines - the look of key_databases.md. */
internal fun spacedHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }.chunked(48).joinToString("\n")
