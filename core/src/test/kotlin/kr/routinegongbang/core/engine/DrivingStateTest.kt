package kr.routinegongbang.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrivingStateTest {

    @Test
    fun `parked only when in P, stopped and not restricted`() {
        assertTrue(DrivingState(gear = Gear.PARK, speedKph = 0.0, uxRestricted = false).isParked)
    }

    @Test
    fun `any driving signal means not parked`() {
        assertFalse(DrivingState(gear = Gear.DRIVE, speedKph = 0.0, uxRestricted = false).isParked)
        assertFalse(DrivingState(gear = Gear.PARK, speedKph = 3.0, uxRestricted = false).isParked)
        assertFalse(DrivingState(gear = Gear.PARK, speedKph = 0.0, uxRestricted = true).isParked)
    }

    @Test
    fun `unknown values are treated as not parked`() {
        assertFalse(DrivingState(gear = null, speedKph = 0.0, uxRestricted = false).isParked)
        assertFalse(DrivingState(gear = Gear.PARK, speedKph = null, uxRestricted = false).isParked)
        assertFalse(DrivingState(gear = Gear.PARK, speedKph = 0.0, uxRestricted = null).isParked)
    }

    @Test
    fun `tiny speed noise still counts as stopped`() {
        assertTrue(DrivingState(gear = Gear.PARK, speedKph = 0.2, uxRestricted = false).isParked)
    }
}
