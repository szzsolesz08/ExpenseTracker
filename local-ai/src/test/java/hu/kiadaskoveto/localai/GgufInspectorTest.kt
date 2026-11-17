package hu.kiadaskoveto.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInspectorTest {

    private class Writer {
        val out = ByteArrayOutputStream()
        private fun bytes(n: Int, f: (ByteBuffer) -> Unit) { val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); f(b); out.write(b.array()) }
        fun u32(v: Int) = bytes(4) { it.putInt(v) }
        fun u64(v: Long) = bytes(8) { it.putLong(v) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        fun kvString(k: String, v: String) { str(k); u32(8); str(v) }
        fun kvU32(k: String, v: Int) { str(k); u32(4); u32(v) }
        fun kvU64(k: String, v: Long) { str(k); u32(10); u64(v) }
        fun kvStringArray(k: String, values: List<String>) { str(k); u32(9); u32(8); u64(values.size.toLong()); values.forEach { str(it) } }
    }

    @Test fun readsHeaderMetadata() {
        val w = Writer()
        w.u32(0x46554747); w.u32(3); w.u64(291); w.u64(6)
        w.kvString("general.architecture", "llama")
        w.kvString("general.name", "Teszt modell")
        w.kvU32("general.file_type", 15)
        w.kvU64("general.parameter_count", 1_100_000_000L)
        w.kvU32("llama.context_length", 4096)
        w.kvStringArray("tokenizer.ggml.tokens", listOf("a", "b", "c"))
        val info = GgufInspector.inspect(ByteArrayInputStream(w.out.toByteArray()))
        assertEquals(3, info.version)
        assertEquals(291L, info.tensorCount)
        assertEquals("llama", info.architecture)
        assertEquals("Teszt modell", info.name)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(1_100_000_000L, info.parameterCount)
        assertEquals(4096L, info.contextLength)
        assertEquals("[3 elem]", info.metadata["tokenizer.ggml.tokens"])
        assertTrue(info.estimatedRamBytes(700L * 1024 * 1024) > 700L * 1024 * 1024)
    }

    @Test(expected = IOException::class)
    fun rejectsNonGguf() {
        GgufInspector.inspect(ByteArrayInputStream("PK\u0003\u0004 not a model".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedFile() {
        val w = Writer(); w.u32(0x46554747); w.u32(3); w.u64(1)
        GgufInspector.inspect(ByteArrayInputStream(w.out.toByteArray()))
    }
}
