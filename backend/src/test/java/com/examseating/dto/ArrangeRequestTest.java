package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ArrangeRequestTest {

    @Test
    void testDefaultsWhenEverythingOmitted() {
        ArrangeRequest req = new ArrangeRequest(
                null, null, null, null,
                null, null, null, null, null, null);
        assertEquals(ArrangeRequest.StrictnessLevel.NORMAL, req.strictness());
        assertTrue(req.clearExisting());
        assertNull(req.groupAttributes(),
                "no explicit groupAttributes -> service falls back to institution scheme / schema discovery");
        assertEquals(GroupMatchMode.ALL, req.groupMatchMode());
    }

    @Test
    void testStrictnessLevelsExist() {
        assertEquals(3, ArrangeRequest.StrictnessLevel.values().length);
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("NORMAL"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("INTERMEDIATE"));
        assertNotNull(ArrangeRequest.StrictnessLevel.valueOf("HARD"));
    }

    @Test
    void testEmptyGroupAttributesNormalizedToNull() {
        ArrangeRequest req = new ArrangeRequest(
                ArrangeRequest.StrictnessLevel.INTERMEDIATE,
                null, null, null,
                List.of(), null,
                null, null, null, null);
        assertNull(req.groupAttributes());
    }

    @Test
    void testGroupAttributesDefaultToALLMatchMode() {
        ArrangeRequest req = new ArrangeRequest(
                null, null, null, null,
                List.of(StudentAttribute.of("course")), null,
                null, null, null, null);
        assertEquals(GroupMatchMode.ALL, req.groupMatchMode());
        assertEquals(List.of(StudentAttribute.of("course")), req.groupAttributes());
    }
}
