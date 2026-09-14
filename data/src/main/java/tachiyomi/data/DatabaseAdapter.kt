package tachiyomi.data

import app.cash.sqldelight.ColumnAdapter
import eu.kanade.tachiyomi.animesource.model.Credit
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object DateColumnAdapter : ColumnAdapter<Date, Long> {
    override fun decode(databaseValue: Long): Date = Date(databaseValue)
    override fun encode(value: Date): Long = value.time
}

private const val LIST_OF_STRINGS_SEPARATOR = ", "
object StringListColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String) = if (databaseValue.isEmpty()) {
        emptyList()
    } else {
        databaseValue.split(LIST_OF_STRINGS_SEPARATOR)
    }
    override fun encode(value: List<String>) = value.joinToString(
        separator = LIST_OF_STRINGS_SEPARATOR,
    )
}

object UpdateStrategyColumnAdapter : ColumnAdapter<UpdateStrategy, Long> {
    override fun decode(databaseValue: Long): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(databaseValue.toInt()) { UpdateStrategy.ALWAYS_UPDATE }

    override fun encode(value: UpdateStrategy): Long = value.ordinal.toLong()
}

object FetchTypeColumnAdapter : ColumnAdapter<FetchType, Long> {
    override fun decode(databaseValue: Long): FetchType =
        FetchType.entries.getOrElse(databaseValue.toInt()) { FetchType.Episodes }

    override fun encode(value: FetchType): Long = value.ordinal.toLong()
}

object CreditListColumnAdapter {
    fun encode(value: List<Credit>): String {
        val arr = JSONArray()
        value.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("name", c.name)
                    c.role?.let { put("role", it) }
                    c.character?.let { put("character", it) }
                    c.image_url?.let { put("image_url", it) }
                    c.url?.let { put("url", it) }
                },
            )
        }
        return arr.toString()
    }

    fun decode(databaseValue: String): List<Credit> {
        if (databaseValue.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(databaseValue)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Credit(
                    name = obj.optString("name", ""),
                    role = obj.optString("role", null),
                    character = obj.optString("character", null),
                    image_url = obj.optString("image_url", null),
                    url = obj.optString("url", null),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
