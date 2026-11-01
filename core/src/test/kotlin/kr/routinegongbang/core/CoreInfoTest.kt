package kr.routinegongbang.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreInfoTest {
    @Test
    fun `schema version starts at 1`() {
        assertEquals(1, CoreInfo.SCHEMA_VERSION)
    }
}
