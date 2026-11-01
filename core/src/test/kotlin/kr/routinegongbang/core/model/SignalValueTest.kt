package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalValueTest {

    private val half = Rounding.Step(0.5)

    @Test
    fun `numbers within half a step are the same`() {
        assertTrue(SignalValue.Num(22.24).sameAs(SignalValue.Num(22.0), half))
        assertTrue(SignalValue.Num(22.24).sameAs(SignalValue.Num(22.26), half), "close values must not fall into different buckets")
        assertFalse(SignalValue.Num(22.3).sameAs(SignalValue.Num(22.0), half))
    }

    @Test
    fun `tolerance is symmetric for negative numbers`() {
        assertTrue(SignalValue.Num(-0.25).sameAs(SignalValue.Num(0.0), half))
        assertTrue(SignalValue.Num(0.25).sameAs(SignalValue.Num(0.0), half))
        assertFalse(SignalValue.Num(-0.3).sameAs(SignalValue.Num(0.0), half))
    }

    @Test
    fun `without rounding tiny floating point noise is ignored but real differences are not`() {
        assertTrue(SignalValue.Num(22.5).sameAs(SignalValue.Num(22.5 + 1e-12), Rounding.None))
        assertFalse(SignalValue.Num(22.5).sameAs(SignalValue.Num(22.6), Rounding.None))
    }

    @Test
    fun `booleans and texts compare by value only`() {
        assertTrue(SignalValue.Bool(true).sameAs(SignalValue.Bool(true), Rounding.None))
        assertFalse(SignalValue.Bool(true).sameAs(SignalValue.Bool(false), Rounding.None))
        assertFalse(SignalValue.Text("ON").sameAs(SignalValue.Bool(true), Rounding.None))
    }

    @Test
    fun `non finite numbers are rejected`() {
        assertFailsWith<IllegalArgumentException> { SignalValue.Num(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SignalValue.Num(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `negative zero is normalized by the factory so equality and sets behave`() {
        assertEquals(SignalValue.num(0.0), SignalValue.num(-0.0))
        assertTrue(SignalValue.num(-0.0) in setOf(SignalValue.num(0.0)))
    }

    @Test
    fun `integer factory produces the same value as a decimal`() {
        assertEquals(SignalValue.Num(3.0), SignalValue.of(3))
    }

    @Test
    fun `bucket gives a stable key for habit grouping`() {
        assertEquals(SignalValue.Num(22.0), SignalValue.Num(22.2).bucket(half))
        assertEquals(SignalValue.Num(22.5), SignalValue.Num(22.4).bucket(half))
        assertEquals(SignalValue.Text("ON"), SignalValue.Text("ON").bucket(half))
    }

    @Test
    fun `value type is reported for validation`() {
        assertEquals(ValueType.BOOLEAN, SignalValue.Bool(true).type)
        assertEquals(ValueType.NUMBER, SignalValue.Num(1.0).type)
        assertEquals(ValueType.ENUM, SignalValue.Text("AUTO").type)
    }
}
