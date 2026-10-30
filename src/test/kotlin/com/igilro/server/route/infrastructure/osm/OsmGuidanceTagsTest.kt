package com.igilro.server.route.infrastructure.osm

import org.junit.jupiter.api.Test
import kotlin.test.*

class OsmGuidanceTagsTest {
    @Test fun `vehicle signals and absent pedestrian crossing tags do not create crossing claims`() {
        assertNull(OsmGuidanceTags.node(mapOf("highway" to "traffic_signals")))
        assertNull(OsmGuidanceTags.node(mapOf("highway" to "crossing","crossing" to "no")))
        assertNull(OsmGuidanceTags.edge(1,mapOf("highway" to "residential","crossing:signals" to "yes")).crossing)
    }
    @Test fun `missing and conflicting signals remain unknown while explicit source values survive`() {
        fun signals(vararg pairs: Pair<String,String>)=OsmGuidanceTags.node(mapOf("highway" to "crossing",*pairs))!!.crossing.trafficSignals
        assertNull(signals());assertNull(signals("crossing" to "uncontrolled"))
        assertEquals(true,signals("crossing:signals" to "yes"));assertEquals(false,signals("crossing:signals" to "no"))
        assertEquals(true,signals("crossing" to "traffic_signals"))
        assertNull(signals("crossing" to "traffic_signals","crossing:signals" to "no"))
    }
    @Test fun `Korean road names and explicit crossing ways are preserved without inventing unnamed roads`() {
        val meta=OsmGuidanceTags.edge(123,mapOf("name:ko" to "서울숲길","name" to "Seoul Forest Road","footway" to "crossing"))
        assertEquals(123L,meta.osmWayId);assertEquals("서울숲길",meta.streetName);assertNotNull(meta.crossing)
        assertNull(OsmGuidanceTags.edge(456,emptyMap()).streetName)
    }
}
