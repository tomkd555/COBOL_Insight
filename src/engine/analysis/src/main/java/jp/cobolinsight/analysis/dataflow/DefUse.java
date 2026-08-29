package jp.cobolinsight.analysis.dataflow;

import java.util.Set;

/** 1文の定義(代入先)変数と参照(被読取)変数。名称は正規化(大文字化)済み。 */
record DefUse(Set<String> defs, Set<String> uses) {

    static final DefUse EMPTY = new DefUse(Set.of(), Set.of());

    DefUse {
        defs = Set.copyOf(defs);
        uses = Set.copyOf(uses);
    }
}
