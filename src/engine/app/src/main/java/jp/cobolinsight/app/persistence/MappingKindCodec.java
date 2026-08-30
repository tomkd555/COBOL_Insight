package jp.cobolinsight.app.persistence;

import jp.cobolinsight.core.linemap.MappingKind;

/**
 * Converts a mapping's kind between engine-api's {@link MappingKind} and the "1:1"/"1:N"/"N:1"
 * strings held by the LINE_MAP.kind column.
 */
public final class MappingKindCodec {

    private MappingKindCodec() {
    }

    public static String toWire(MappingKind kind) {
        return switch (kind) {
            case ONE_TO_ONE -> "1:1";
            case ONE_TO_MANY -> "1:N";
            case MANY_TO_ONE -> "N:1";
        };
    }

    public static MappingKind fromWire(String wire) {
        return switch (wire) {
            case "1:1" -> MappingKind.ONE_TO_ONE;
            case "1:N" -> MappingKind.ONE_TO_MANY;
            case "N:1" -> MappingKind.MANY_TO_ONE;
            default -> throw new IllegalArgumentException("unknown mapping kind: " + wire);
        };
    }
}
