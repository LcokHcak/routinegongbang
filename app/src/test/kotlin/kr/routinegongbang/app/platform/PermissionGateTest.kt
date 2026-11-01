package kr.routinegongbang.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With permissions denied, the app must not sit at "차량 상태 확인 중" without saying why. */
class PermissionGateTest {

    @Test
    fun `missing car permissions block, and are named for the banner`() {
        val missing = setOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_CLIMATE", "android.permission.POST_NOTIFICATIONS")
        assertEquals(setOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_CLIMATE"), PermissionGate.blocking(missing))
    }

    @Test
    fun `only notifications missing does not block, it only silences notices`() {
        assertTrue(PermissionGate.blocking(setOf("android.permission.POST_NOTIFICATIONS")).isEmpty())
        assertTrue(PermissionGate.notifyingOff(setOf("android.permission.POST_NOTIFICATIONS")))
    }

    @Test
    fun `nothing missing, nothing to say`() {
        assertTrue(PermissionGate.blocking(emptySet()).isEmpty())
        assertEquals(false, PermissionGate.notifyingOff(emptySet()))
        assertNull(PermissionGate.banner(emptySet()))
    }

    @Test
    fun `the banner says parking is unknown only when speed or gear cannot be read`() {
        // With only the climate permission missing, "정차 여부를 알 수 없어요" would be wrong: parking is known.
        assertEquals(PermissionGate.Banner.PARKING_UNKNOWN, PermissionGate.banner(setOf("android.car.permission.CAR_SPEED")))
        assertEquals(PermissionGate.Banner.PARKING_UNKNOWN, PermissionGate.banner(setOf("android.car.permission.CAR_POWERTRAIN", "android.permission.POST_NOTIFICATIONS")))
        assertEquals(PermissionGate.Banner.SOME_CAR_DATA, PermissionGate.banner(setOf("android.car.permission.CAR_ENERGY")))
        assertEquals(PermissionGate.Banner.NOTIFICATIONS_OFF, PermissionGate.banner(setOf("android.permission.POST_NOTIFICATIONS")))
    }
}
