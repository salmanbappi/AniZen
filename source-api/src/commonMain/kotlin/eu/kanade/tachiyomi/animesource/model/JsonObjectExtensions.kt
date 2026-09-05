package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject

val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty
