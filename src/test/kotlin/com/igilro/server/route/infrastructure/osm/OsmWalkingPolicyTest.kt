package com.igilro.server.route.infrastructure.osm

import org.junit.jupiter.api.Test
import kotlin.test.*

class OsmWalkingPolicyTest {
    private val policy = OsmWalkingPolicy()

    @Test
    fun `private forbidden and conditional access is excluded`() {
        listOf(mapOf("access" to "private"), mapOf("foot" to "no"), mapOf("foot" to "use_sidepath"), mapOf("foot:conditional" to "yes @ (Mo-Fr)"), mapOf("opening_hours" to "09:00-18:00")).forEach {
            assertNull(policy.way(mapOf("highway" to "footway") + it))
        }
    }

    @Test
    fun `specific foot permission can override generic access`() {
        assertNotNull(policy.way(mapOf("highway" to "footway", "access" to "no", "foot" to "yes")))
    }

    @Test
    fun `motorway is excluded and major roads need foot or sidewalk evidence`() {
        assertNull(policy.way(mapOf("highway" to "motorway", "foot" to "yes")))
        assertNull(policy.way(mapOf("highway" to "primary")))
        assertNotNull(policy.way(mapOf("highway" to "primary", "sidewalk" to "both")))
    }

    @Test
    fun `vehicle one way does not imply pedestrian one way`() {
        assertTrue(policy.way(mapOf("highway" to "residential", "oneway" to "yes"))!!.backward)
        assertFalse(policy.way(mapOf("highway" to "footway", "oneway:foot" to "yes"))!!.backward)
    }

    @Test
    fun `directional access and unknown escalator direction are respected`() {
        assertFalse(policy.way(mapOf("highway" to "footway", "foot:backward" to "private"))!!.backward)
        assertFalse(policy.way(mapOf("highway" to "footway", "access:backward" to "no"))!!.backward)
        assertNull(policy.way(mapOf("highway" to "steps", "conveying" to "reversible")))
    }

    @Test
    fun `blocked barriers and locked gates cannot connect edges`() {
        assertFalse(policy.nodeAllowed(mapOf("barrier" to "wall")))
        assertFalse(policy.nodeAllowed(mapOf("barrier" to "gate", "locked" to "yes")))
        assertTrue(policy.nodeAllowed(mapOf("barrier" to "bollard")))
    }
}
