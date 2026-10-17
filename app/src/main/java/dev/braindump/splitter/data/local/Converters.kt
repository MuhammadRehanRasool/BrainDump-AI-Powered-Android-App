package dev.braindump.splitter.data.local

import androidx.room.TypeConverter
import dev.braindump.splitter.domain.model.IdeaStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(stringList, value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList()
        else runCatching { Json.decodeFromString(stringList, value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromStatus(status: IdeaStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): IdeaStatus =
        IdeaStatus.entries.firstOrNull { it.name == value } ?: IdeaStatus.PENDING

    private companion object {
        val stringList = ListSerializer(String.serializer())
    }
}
