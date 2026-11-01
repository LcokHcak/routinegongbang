package kr.routinegongbang.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

enum class ValueType { BOOLEAN, NUMBER, ENUM }

/** How values of a signal are compared (habit detection and WriteLedger). */
sealed interface Rounding {
    data object None : Rounding
    data class Step(val step: Double) : Rounding {
        init {
            require(step > 0 && step.isFinite()) { "Rounding step must be a positive finite number" }
        }
    }
}

/** A vehicle signal value. Serialized as a bare JSON primitive: true / 22.5 / "AUTO". */
@Serializable(with = SignalValueSerializer::class)
sealed interface SignalValue {
    val type: ValueType

    data class Bool(val value: Boolean) : SignalValue {
        override val type: ValueType get() = ValueType.BOOLEAN
    }

    /** Finite numbers only, so comparisons and rounding can never throw inside the engine loop. */
    data class Num(val value: Double) : SignalValue {
        init {
            require(value.isFinite()) { "SignalValue.Num must be finite, was $value" }
        }

        override val type: ValueType get() = ValueType.NUMBER
    }

    data class Text(val value: String) : SignalValue {
        override val type: ValueType get() = ValueType.ENUM
    }

    /** Tolerance comparison: numbers within half a rounding step (or float noise) are the same. */
    fun sameAs(other: SignalValue, rounding: Rounding): Boolean {
        if (this !is Num || other !is Num) return this == other
        val tolerance = when (rounding) {
            is Rounding.Step -> rounding.step / 2 + EPSILON
            Rounding.None -> EPSILON
        }
        return abs(value - other.value) <= tolerance
    }

    /**
     * `this op other` with the rounding tolerance of [sameAs]: within tolerance counts as equal, so
     * 23.8 GE 24 holds for a 0.5 step. Null when undefined (type mismatch, ordering on non-numbers).
     */
    fun satisfies(op: CompareOp, other: SignalValue, rounding: Rounding): Boolean? {
        if (type != other.type) return null
        val same = sameAs(other, rounding)
        if (op == CompareOp.EQ) return same
        if (op == CompareOp.NE) return !same
        val a = (this as? Num)?.value ?: return null
        val b = (other as? Num)?.value ?: return null
        return same && (op == CompareOp.LE || op == CompareOp.GE) || !same && op.holds(a, b)
    }

    /** Stable grouping key for habit detection (nearest step). Non-numbers are returned unchanged. */
    fun bucket(rounding: Rounding): SignalValue =
        if (this is Num && rounding is Rounding.Step) num(Math.rint(value / rounding.step) * rounding.step) else this

    companion object {
        private const val EPSILON = 1e-9

        fun of(value: Int): SignalValue = Num(value.toDouble())

        /** Preferred number factory: normalizes -0.0 to 0.0 so equality and sets behave. */
        fun num(value: Double): SignalValue = Num(value + 0.0)
    }
}

object SignalValueSerializer : KSerializer<SignalValue> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SignalValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SignalValue) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("SignalValue supports JSON only")
        val primitive = when (value) {
            is SignalValue.Bool -> JsonPrimitive(value.value)
            is SignalValue.Num -> JsonPrimitive(value.value)
            is SignalValue.Text -> JsonPrimitive(value.value)
        }
        json.encodeJsonElement(primitive)
    }

    override fun deserialize(decoder: Decoder): SignalValue {
        val json = decoder as? JsonDecoder ?: throw SerializationException("SignalValue supports JSON only")
        val element = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("SignalValue must be a JSON primitive")
        if (element.isString) return SignalValue.Text(element.content)
        element.booleanOrNull?.let { return SignalValue.Bool(it) }
        val number = element.doubleOrNull ?: throw SerializationException("Unsupported SignalValue: $element")
        if (!number.isFinite()) throw SerializationException("SignalValue number must be finite: $element")
        return SignalValue.num(number)
    }
}
