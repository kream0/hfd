package app.hfd.data

import android.util.Log
import app.hfd.core.HfdJson
import kotlinx.serialization.KSerializer
import java.io.File

/** Tiny JSON-on-disk store with atomic replace (write `.tmp`, then rename). */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    @Synchronized
    fun read(): T = try {
        if (file.exists()) HfdJson.decodeFromString(serializer, file.readText()) else default()
    } catch (e: Exception) {
        Log.w("JsonFile", "Could not read ${file.name}, starting fresh", e)
        // Keep the unreadable file around rather than overwriting it on the next write.
        runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
        default()
    }

    @Synchronized
    fun write(value: T) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(HfdJson.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w("JsonFile", "Could not write ${file.name}", e)
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
    }
}
