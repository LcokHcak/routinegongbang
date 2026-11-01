package kr.routinegongbang.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * JSON codec for routines. Decoding never throws for bad input; callers handle the failure
 * (e.g. a malformed shared routine). Size is capped before parsing to bound recursion depth.
 */
object RoutineJson {
    /** Routines are small; this bound also limits nesting depth for untrusted shared input. */
    const val MAX_INPUT_LENGTH: Int = 16_384

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(routine: Routine): String = json.encodeToString(Routine.serializer(), routine)

    /** Upper bound for a stored routine list (the app keeps at most a few hundred routines). */
    const val MAX_LIST_LENGTH: Int = 200 * MAX_INPUT_LENGTH

    fun encodeList(routines: List<Routine>): String = json.encodeToString(ListSerializer(Routine.serializer()), routines)

    /**
     * Decodes a stored list entry by entry, so one entry from a newer or broken version does not lose the rest.
     * Fails as a whole only when the text is not a JSON array or is too large.
     */
    fun decodeList(text: String): Result<List<Result<Routine>>> {
        if (text.length > MAX_LIST_LENGTH) return Result.failure(IllegalArgumentException("Routine list exceeds $MAX_LIST_LENGTH characters"))
        val array = try {
            json.parseToJsonElement(text) as? JsonArray ?: return Result.failure(IllegalArgumentException("Routine list is not a JSON array"))
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        return Result.success(array.map { decodeElement(it) })
    }

    private fun decodeElement(element: JsonElement): Result<Routine> = try {
        Result.success(json.decodeFromJsonElement(Routine.serializer(), element))
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

    fun decode(text: String): Result<Routine> {
        if (text.length > MAX_INPUT_LENGTH) {
            return Result.failure(IllegalArgumentException("Routine JSON exceeds $MAX_INPUT_LENGTH characters"))
        }
        // SerializationException is an IllegalArgumentException; Errors (e.g. OOM) are not swallowed.
        return try {
            Result.success(json.decodeFromString(Routine.serializer(), text))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}
