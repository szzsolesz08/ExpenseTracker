package hu.kiadaskoveto.localai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A category the model may choose from. */
data class CategoryOption(val key: String, val name: String)

/** A suggestion from the local model. Never used for amounts or totals. */
data class AiSuggestion(val categoryKey: String?, val readableName: String?, val confidence: Double)

/**
 * Optional on-device language model used only to help interpret ambiguous product names and
 * categories. Implementations must run fully offline, must not do arithmetic on prices,
 * and must return null when unsure.
 */
interface LocalLanguageModel {
    val isReady: Boolean
    val statusMessage: String
    suspend fun suggest(rawName: String, options: List<CategoryOption>): AiSuggestion?
    fun close() {}
}

/**
 * The runtime used when no on-device inference engine is bundled in the build. The app's rule-based
 * engine (dictionary, rules, user corrections, fuzzy matching) does all the work.
 */
class UnavailableLanguageModel(override val statusMessage: String) : LocalLanguageModel {
    override val isReady: Boolean = false
    override suspend fun suggest(rawName: String, options: List<CategoryOption>): AiSuggestion? = null
}

data class InstalledModel(val file: File, val info: GgufInfo, val sizeBytes: Long)

data class DeviceCompatibility(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val requiredRamBytes: Long,
    val is64Bit: Boolean,
) {
    val fits: Boolean get() = is64Bit && requiredRamBytes < totalRamBytes * 0.6
}

/**
 * Manages a manually imported GGUF model in private storage. Nothing is ever downloaded:
 * the user chooses a file with the system picker.
 */
class LocalModelManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /** Whether this build contains an on-device inference engine (llama.cpp JNI). */
    val runtimeBundled: Boolean = false

    fun installed(): InstalledModel? {
        val f = dir.listFiles { file -> file.name.endsWith(".gguf") }?.firstOrNull() ?: return null
        return runCatching { InstalledModel(f, f.inputStream().use { GgufInspector.inspect(it) }, f.length()) }.getOrNull()
    }

    /** Copies the chosen model into private storage after validating its header. */
    suspend fun import(uri: Uri, displayName: String, onProgress: (Float) -> Unit): InstalledModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val info = resolver.openInputStream(uri)?.use { GgufInspector.inspect(it) } ?: throw IOException("A fájl nem olvasható.")
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val freeBytes = dir.usableSpace
        if (total > 0 && total > freeBytes - 200L * 1024 * 1024) throw IOException("Nincs elég szabad tárhely a modellhez.")
        dir.listFiles()?.forEach { it.delete() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        val target = File(dir, safeName)
        val tmp = File(dir, "$safeName.part")
        resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } ?: throw IOException("A fájl nem olvasható.")
        if (!tmp.renameTo(target)) throw IOException("A modell mentése nem sikerült.")
        InstalledModel(target, info, target.length())
    }

    fun delete() { dir.listFiles()?.forEach { it.delete() } }

    fun compatibility(model: InstalledModel?): DeviceCompatibility {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val required = model?.let { it.info.estimatedRamBytes(it.sizeBytes) } ?: 0L
        return DeviceCompatibility(mi.totalMem, mi.availMem, required, android.os.Process.is64Bit())
    }

    /** The language model to use for ambiguity resolution. */
    fun createModel(): LocalLanguageModel {
        val model = installed()
        return when {
            !runtimeBundled -> UnavailableLanguageModel(
                "Ebben a verzióban nincs beépített LLM-futtatómotor. A kategorizálást a helyi szabályalapú motor végzi; " +
                    "az importált modell a későbbi llama.cpp integrációhoz megmarad."
            )
            model == null -> UnavailableLanguageModel("Nincs importált modell.")
            else -> UnavailableLanguageModel("A modell betöltése nem sikerült.")
        }
    }
}
