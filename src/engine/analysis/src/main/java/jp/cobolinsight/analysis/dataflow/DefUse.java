package jp.cobolinsight.analysis.dataflow;

import java.util.Set;

/** The definition (assignment target) and use (read) variables of one statement. Names are already normalized (uppercased). */
record DefUse(Set<String> defs, Set<String> uses) {

    static final DefUse EMPTY = new DefUse(Set.of(), Set.of());

    DefUse {
        defs = Set.copyOf(defs);
        uses = Set.copyOf(uses);
    }
}
