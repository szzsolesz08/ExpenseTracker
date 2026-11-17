package hu.kiadaskoveto.localai

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata read from a GGUF model file header. */
data class GgufInfo(
    val version: Int,
    val tensorCount: Long,
    val architecture: String?,
    val name: String?,
    val sizeLabel: String?,
    val parameterCount: Long?,
    val contextLength: Long?,
    val fileType: Int?,
    val quantization: String?,
    val metadata: Map<String, String>,
) {
    /** Rough RAM need: weights (≈ file size) + KV cache / runtime overhead. */
    fun estimatedRamBytes(fileSizeBytes: Long, contextTokens: Int = 2048): Long {
        val kvOverhead = 64L * 1024 * 1024 + contextTokens.toLong() * 256 * 1024
        return (fileSizeBytes * 1.1).toLong() + kvOverhead
    }
}

/**
 * Minimal GGUF (v2/v3) header reader — validates a manually imported model file without
 * loading it. See https://github.com/ggml-org/ggml/blob/master/docs/gguf.md for the format.
 */
object GgufInspector {

    private const val MAGIC = 0x46554747 // "GGUF" little endian
    private const val MAX_STRING = 1 shl 20

    private val FILE_TYPES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
        16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 24 to "IQ1_S",
        25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 32 to "BF16",
    )

    @Throws(IOException::class)
    fun inspect(input: InputStream): GgufInfo {
        val r = LittleEndianReader(input.buffered())
        if (r.int() != MAGIC) throw IOException("A fájl nem GGUF modell.")
        val version = r.int()
        if (version !in 2..3) throw IOException("Nem támogatott GGUF verzió: $version")
        val tensorCount = r.long()
        val kvCount = r.long()
        if (kvCount < 0 || kvCount > 100_000) throw IOException("Sérült GGUF fejléc.")
        val meta = LinkedHashMap<String, String>()
        repeat(kvCount.toInt()) {
            val key = r.string()
            val type = r.int()
            val value = readValue(r, type, depth = 0)
            if (value != null) meta[key] = value
        }
        val arch = meta["general.architecture"]
        val fileType = meta["general.file_type"]?.toIntOrNull()
        return GgufInfo(
            version = version,
            tensorCount = tensorCount,
            architecture = arch,
            name = meta["general.name"],
            sizeLabel = meta["general.size_label"],
            parameterCount = meta["general.parameter_count"]?.toLongOrNull(),
            contextLength = arch?.let { meta["$it.context_length"]?.toLongOrNull() },
            fileType = fileType,
            quantization = fileType?.let { FILE_TYPES[it] ?: "type $it" },
            metadata = meta,
        )
    }

    /** Reads a value; arrays are skipped (summarized) to keep memory small. */
    private fun readValue(r: LittleEndianReader, type: Int, depth: Int): String? = when (type) {
        0 -> r.u8().toString()            // UINT8
        1 -> r.i8().toString()            // INT8
        2 -> r.u16().toString()           // UINT16
        3 -> r.i16().toString()           // INT16
        4 -> (r.int().toLong() and 0xffffffffL).toString() // UINT32
        5 -> r.int().toString()           // INT32
        6 -> r.float().toString()         // FLOAT32
        7 -> (r.u8() != 0).toString()     // BOOL
        8 -> r.string()                   // STRING
        9 -> {                            // ARRAY
            val elemType = r.int()
            val n = r.long()
            if (depth > 2 || n < 0) throw IOException("Sérült GGUF tömb.")
            var i = 0L
            while (i < n) { readValue(r, elemType, depth + 1); i++ }
            if (depth == 0) "[$n elem]" else null
        }
        10 -> r.long().toULong().toString() // UINT64
        11 -> r.long().toString()         // INT64
        12 -> r.double().toString()       // FLOAT64
        else -> throw IOException("Ismeretlen GGUF értéktípus: $type")
    }

    private class LittleEndianReader(private val input: InputStream) {
        private val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        private fun fill(n: Int): ByteBuffer {
            buf.clear()
            var read = 0
            while (read < n) {
                val c = input.read(buf.array(), read, n - read)
                if (c < 0) throw EOFException("Váratlan fájlvég a GGUF fejlécben.")
                read += c
            }
            buf.limit(n)
            return buf
        }
        fun u8(): Int = fill(1).get(0).toInt() and 0xff
        fun i8(): Int = fill(1).get(0).toInt()
        fun u16(): Int = fill(2).getShort(0).toInt() and 0xffff
        fun i16(): Int = fill(2).getShort(0).toInt()
        fun int(): Int = fill(4).getInt(0)
        fun long(): Long = fill(8).getLong(0)
        fun float(): Float = fill(4).getFloat(0)
        fun double(): Double = fill(8).getDouble(0)
        fun string(): String {
            val len = long()
            if (len < 0 || len > MAX_STRING) throw IOException("Sérült GGUF szöveg.")
            val bytes = ByteArray(len.toInt())
            var read = 0
            while (read < bytes.size) {
                val c = input.read(bytes, read, bytes.size - read)
                if (c < 0) throw EOFException()
                read += c
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}

