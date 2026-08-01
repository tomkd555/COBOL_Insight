package jp.cobolinsight.engineapi.bms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BmsModelTest {

    private static BmsField field(String name) {
        return new BmsField(name, 3, 5, 8, "UNPROT");
    }

    @Test
    void fieldRejectsInvalidPositionAndLength() {
        assertThrows(IllegalArgumentException.class, () -> new BmsField("F1", 0, 5, 8, ""));
        assertThrows(IllegalArgumentException.class, () -> new BmsField("F1", 3, 0, 8, ""));
        assertThrows(IllegalArgumentException.class, () -> new BmsField("F1", 3, 5, -1, ""));
    }

    @Test
    void fieldAllowsEmptyNameForUnnamedLiteralField() {
        BmsField unnamed = new BmsField("", 1, 1, 10, "PROT");
        assertEquals("", unnamed.name());
    }

    @Test
    void mapLooksUpFieldByName() {
        BmsMap map = new BmsMap("SYKMAP", 24, 80, List.of(field("USERID"), field("PASSWD")));
        assertEquals("PASSWD", map.field("PASSWD").orElseThrow().name());
        assertTrue(map.field("MISSING").isEmpty());
    }

    @Test
    void mapRejectsInvalidSize() {
        assertThrows(IllegalArgumentException.class, () -> new BmsMap("M", 0, 80, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BmsMap("M", 24, 0, List.of()));
    }

    @Test
    void mapsetLooksUpMapByName() {
        BmsMap map = new BmsMap("SYKMAP", 24, 80, List.of());
        BmsMapset mapset = new BmsMapset("SYKMSET", "SYKMSET.bms", List.of(map));
        assertEquals("SYKMAP", mapset.map("SYKMAP").orElseThrow().name());
        assertTrue(mapset.map("OTHER").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> mapset.maps().clear());
    }

    @Test
    void mapsetRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new BmsMapset(" ", "a.bms", List.of()));
    }
}
