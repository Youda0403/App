package com.pairplay.app

import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipTypeTest {

    @Test
    fun `알 수 없는 이름은 기본값으로 떨어진다`() {
        assertEquals(RelationshipType.FRIENDS, RelationshipType.fromName("NOT_A_REAL_TYPE"))
        assertEquals(RelationshipType.FRIENDS, RelationshipType.fromName(null))
        assertEquals(RelationshipDirection.MUTUAL, RelationshipDirection.fromName(null))
    }

    @Test
    fun `짝사랑만 방향 지정이 필요하다`() {
        assertTrue(RelationshipType.ONE_SIDED_LOVE.needsDirection)
        assertFalse(RelationshipType.LOVERS.needsDirection)
        assertFalse(RelationshipType.FRIENDS.needsDirection)
    }
}
