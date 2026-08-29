package jp.cobolinsight.app.persistence;

import jp.cobolinsight.core.linemap.MappingKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappingKindCodecTest {

    @Test
    void encodesEachKindToWireString() {
        assertEquals("1:1", MappingKindCodec.toWire(MappingKind.ONE_TO_ONE));
        assertEquals("1:N", MappingKindCodec.toWire(MappingKind.ONE_TO_MANY));
        assertEquals("N:1", MappingKindCodec.toWire(MappingKind.MANY_TO_ONE));
    }

    @Test
    void decodesEachWireString() {
        assertEquals(MappingKind.ONE_TO_ONE, MappingKindCodec.fromWire("1:1"));
        assertEquals(MappingKind.ONE_TO_MANY, MappingKindCodec.fromWire("1:N"));
        assertEquals(MappingKind.MANY_TO_ONE, MappingKindCodec.fromWire("N:1"));
    }

    @Test
    void roundTripsEveryKind() {
        for (MappingKind kind : MappingKind.values()) {
            assertEquals(kind, MappingKindCodec.fromWire(MappingKindCodec.toWire(kind)));
        }
    }

    @Test
    void rejectsUnknownWireString() {
        assertThrows(IllegalArgumentException.class, () -> MappingKindCodec.fromWire("2:2"));
    }
}
