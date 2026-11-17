package org.rafendb.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ComponentTest {

    @Test
    void findsByShortNameIgnoringCase() {
        assertEquals(Component.QP, Component.fromShortName("qp"));
        assertEquals(Component.SM, Component.fromShortName("Sm"));
        assertEquals(Component.CCM, Component.fromShortName(" CCM "));
        assertEquals(Component.FRM, Component.fromShortName("frm"));
        assertEquals(Component.QO, Component.fromShortName("QO"));
        assertEquals(Component.GENERAL, Component.fromShortName("gen"));
    }

    @Test
    void rejectsUnknownNames() {
        assertThrows(IllegalArgumentException.class, () -> Component.fromShortName("storage"));
        assertThrows(IllegalArgumentException.class, () -> Component.fromShortName(""));
    }
}
