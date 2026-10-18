package dev.braindump.splitter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.MicroTask
import dev.braindump.splitter.domain.model.RefineTurn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "ideas",
    indices = [Index(value = ["created_at"]), Index(value = ["status"])],
)
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "raw_input")
    val rawInput: String,
    @ColumnInfo(name = "idea_title")
    val ideaTitle: String? = null,
    @ColumnInfo(name = "raw_concept")
    val rawConcept: String? = null,
    @ColumnInfo(name = "tech_stack_anchors")
    val techStackAnchors: List<String> = emptyList(),
    @ColumnInfo(name = "immediate_micro_tasks")
    val immediateMicroTasks: List<String> = emptyList(),
    /** [IdeaCategory] name; null until the idea is split (or for ideas saved before categories existed). */
    @ColumnInfo(name = "category")
    val category: String? = null,
    /** Bit i set = micro task i checked off. */
    @ColumnInfo(name = "completed_mask")
    val completedMask: Int = 0,
    @ColumnInfo(name = "status")
    val status: IdeaStatus = IdeaStatus.PENDING,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** The refine chat as a JSON list of [RefineTurn]; null until the idea is first refined. */
    @ColumnInfo(name = "refinements")
    val refinements: String? = null,
)

fun IdeaEntity.refineTurns(): List<RefineTurn> =
    refinements?.let { runCatching { Json.decodeFromString(refineTurnList, it) }.getOrNull() }.orEmpty()

fun IdeaEntity.withTurn(turn: RefineTurn): IdeaEntity =
    copy(refinements = Json.encodeToString(refineTurnList, refineTurns() + turn))

private val refineTurnList = ListSerializer(RefineTurn.serializer())

fun IdeaEntity.toDomain(): Idea = Idea(
    id = id,
    rawInput = rawInput,
    title = ideaTitle.orEmpty(),
    concept = rawConcept.orEmpty(),
    techStack = techStackAnchors,
    tasks = immediateMicroTasks.mapIndexed { index, text ->
        MicroTask(index = index, text = text, done = (completedMask and (1 shl index)) != 0)
    },
    category = IdeaCategory.parse(category),
    status = status,
    error = lastError,
    createdAt = createdAt,
    refinements = refineTurns(),
)
