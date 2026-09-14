package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FilterSerializer {

    fun serialize(filters: AnimeFilterList): String {
        return buildJsonArray {
            filters.forEach { filter ->
                val json = serializeFilter(filter)
                if (json != null) {
                    add(json)
                }
            }
        }.toString()
    }

    private fun serializeFilter(filter: AnimeFilter<*>): JsonElement? {
        return when (filter) {
            is AnimeFilter.Header -> null
            is AnimeFilter.Separator -> null
            is AnimeFilter.CheckBox -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("CheckBox"))
                put("state", JsonPrimitive(filter.state))
            }
            is AnimeFilter.TriState -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("TriState"))
                put("state", JsonPrimitive(filter.state))
            }
            is AnimeFilter.Text -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("Text"))
                put("state", JsonPrimitive(filter.state))
            }
            is AnimeFilter.Select<*> -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("Select"))
                put("state", JsonPrimitive(filter.state))
            }
            is AnimeFilter.Sort -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("Sort"))
                val selection = filter.state
                if (selection != null) {
                    put(
                        "state",
                        buildJsonObject {
                            put("index", JsonPrimitive(selection.index))
                            put("ascending", JsonPrimitive(selection.ascending))
                        },
                    )
                }
            }
            is AnimeFilter.Group<*> -> buildJsonObject {
                put("name", JsonPrimitive(filter.name))
                put("type", JsonPrimitive("Group"))
                put(
                    "state",
                    buildJsonArray {
                        filter.state.forEach {
                            if (it is AnimeFilter<*>) {
                                serializeFilter(it)?.let { add(it) }
                            }
                        }
                    },
                )
            }
        }
    }

    fun deserialize(filters: AnimeFilterList, json: String) {
        try {
            val jsonArray = Json.parseToJsonElement(json).jsonArray
            jsonArray.forEach { element ->
                val jsonObject = element.jsonObject
                val name = jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val type = jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val state = jsonObject["state"] ?: return@forEach

                val filter = filters.find { it.name == name } ?: return@forEach
                deserializeFilter(filter, type, state)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun deserializeFilter(filter: AnimeFilter<*>, type: String, state: JsonElement) {
        when (filter) {
            is AnimeFilter.CheckBox -> if (type == "CheckBox") filter.state = state.jsonPrimitive.booleanOrNull ?: filter.state
            is AnimeFilter.TriState -> if (type == "TriState") filter.state = state.jsonPrimitive.intOrNull ?: filter.state
            is AnimeFilter.Text -> if (type == "Text") filter.state = state.jsonPrimitive.contentOrNull ?: filter.state
            is AnimeFilter.Select<*> -> if (type == "Select") filter.state = state.jsonPrimitive.intOrNull ?: filter.state
            is AnimeFilter.Sort -> {
                if (type == "Sort") {
                    val selection = state.jsonObject
                    filter.state = AnimeFilter.Sort.Selection(
                        index = selection["index"]?.jsonPrimitive?.intOrNull ?: 0,
                        ascending = selection["ascending"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                }
            }
            is AnimeFilter.Group<*> -> {
                if (type == "Group") {
                    val groupArray = state.jsonArray
                    groupArray.forEach { element ->
                        val jsonObject = element.jsonObject
                        val subName = jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val subType = jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val subState = jsonObject["state"] ?: return@forEach

                        val subFilter = filter.state.filterIsInstance<AnimeFilter<*>>().find { it.name == subName } ?: return@forEach
                        deserializeFilter(subFilter, subType, subState)
                    }
                }
            }
            else -> {}
        }
    }
}
