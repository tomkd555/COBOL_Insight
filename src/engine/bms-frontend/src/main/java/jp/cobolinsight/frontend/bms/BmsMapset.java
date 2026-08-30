package jp.cobolinsight.frontend.bms;

import java.util.List;

/** A mapset defined by DFHMSD. */
public record BmsMapset(String name, int sourceLine, List<BmsMap> maps) {

    public BmsMapset {
        maps = List.copyOf(maps);
    }
}
