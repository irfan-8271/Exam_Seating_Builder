package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ArrangeRequestTest {

    @Test
    void testDefaultsWhenEverythingOmitted() {
        ArrangeRequest req = new ArrangeRequest(null, null, null, null, null, null, null);
        assertEquals(ArrangeRequest.StrictnessLevel.BASIC, req.strictness());
        assertNotNull(req.benchPairingRule());
        assertTrue(req.horizontalDeptIsolation());
        assertTrue(req.clearExisting());
        assertNull(req.groupAttributes(), "no group attributes -> engine falls back to conflictScope");
    }

    @Test
    void testLegacyAliasesExist() {
        // NORMAL and EXTREME are deprecated aliases of BASIC and ADVANCED and
        // must keep deserializing for old clients.
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("NORMAL"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("EXTREME"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("BASIC"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("INTERMEDIATE"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("ADVANCED"));
    }

    @Test
    void testEmptyGroupAttributesNormalizedToNull() {
        ArrangeRequest req = new ArrangeRequest(
                ArrangeRequest.StrictnessLevel.INTERMEDIATE,
                null, null, null,
                null, null, null,
                List.of(), null,
                null, null, null);
        assertNull(req.groupAttributes());
    }

    @Test
    void testGroupAttributesDefaultToALLMatchMode() {
        ArrangeRequest req = new ArrangeRequest(
                null, null, null, null,
                null, null, null,
                List.of(StudentAttribute.COURSE), null,
                null, null, null);
        assertEquals(GroupMatchMode.ALL, req.groupMatchMode());
    }
}
