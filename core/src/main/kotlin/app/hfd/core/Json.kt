package app.hfd.core

import kotlinx.serialization.json.Json

/** JSON settings shared by every file the app reads or writes. */
val HfdJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Compact variant for the append-only event log (one object per line). */
val HfdJsonLine = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
