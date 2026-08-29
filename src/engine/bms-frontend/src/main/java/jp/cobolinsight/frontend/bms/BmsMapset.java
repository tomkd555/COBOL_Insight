package jp.cobolinsight.frontend.bms;

import java.util.List;

/** DFHMSD が定義するマップセット。 */
public record BmsMapset(String name, int sourceLine, List<BmsMap> maps) {

    public BmsMapset {
        maps = List.copyOf(maps);
    }
}
