package app.hfd.data

import android.content.Context
import android.net.Uri
import app.hfd.Graph
import app.hfd.core.HfdJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

/** A manual backup: the whole event log plus settings and chosen ranges. */
@Serializable
data class BackupFile(
    val schema: Int = SCHEMA,
    val app: String = "hfd",
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings? = null,
    val ranges: Map<String, SubRange> = emptyMap(),
    /** Lines of progress/events.jsonl, verbatim. */
    val events: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

object Backup {
    fun fileName(): String = "hfd-backup-${java.time.LocalDate.now()}.json"

    suspend fun export(context: Context, uri: Uri) {
        val file = BackupFile(
            settings = Graph.settings.current,
            ranges = Graph.ranges.ranges.value,
            events = Graph.progress.logLines(),
        )
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write there")
            out.use { it.write(HfdJson.encodeToString(BackupFile.serializer(), file).toByteArray()) }
        }
    }

    /** Merges a backup into the phone's progress; returns how many new events it brought. */
    suspend fun import(context: Context, uri: Uri): Int {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw IOException("Can't read the file")
        }
        val file = HfdJson.decodeFromString(BackupFile.serializer(), text)
        if (file.app != "hfd") throw IOException("Not an HFD backup")
        if (file.schema > BackupFile.SCHEMA) throw IOException("Made by a newer version of HFD")
        val added = Graph.progress.importLines(file.events)
        file.ranges.forEach { (id, r) -> Graph.ranges.set(id, r) }
        file.settings?.let { s -> Graph.settings.update { s } }
        return added
    }
}
