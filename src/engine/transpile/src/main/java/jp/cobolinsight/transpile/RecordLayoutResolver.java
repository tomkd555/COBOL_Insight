package jp.cobolinsight.transpile;

import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Occurs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a record layout ({@link LayoutField}) from a data item tree. Item length comes from
 * {@link PictureType#byteLength()}; the cumulative offset is the running sum of byteLength x occurs
 * over preceding same-level siblings. A REDEFINES target shares the same starting offset as the
 * item it redefines and does not add to the following offset. A group item's length is the sum of
 * the children that contribute to it (i.e. not REDEFINES). No side effects, deterministic
 * (children are processed in list order).
 */
public final class RecordLayoutResolver {

    private RecordLayoutResolver() {
    }

    /** Resolves the layout rooted at a level-01 (or any level) item. The root's offset is 0. */
    public static LayoutField resolve(DataItem record) {
        return resolveItem(record, 0);
    }

    private static LayoutField resolveItem(DataItem item, int startOffset) {
        int occurs = item.occurs().map(Occurs::maxTimes).orElse(1);
        if (item.picture().isPresent()) {
            PictureType pt = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            return new LayoutField(item.name(), item.level(), startOffset, pt.byteLength(), occurs,
                    Optional.of(pt), item.redefines(), item.conditionNames(), List.of());
        }
        List<LayoutField> children = new ArrayList<>();
        // COBOL data names are case-insensitive. Some assets have inconsistent casing between a
        // REDEFINES target name and the declared name, so uppercase both before matching.
        Map<String, Integer> siblingOffset = new HashMap<>();
        int cursor = startOffset;
        for (DataItem child : item.children()) {
            int childStart;
            if (child.redefines().isPresent()) {
                Integer target = siblingOffset.get(upper(child.redefines().get()));
                childStart = target != null ? target : cursor;
            } else {
                childStart = cursor;
            }
            LayoutField resolved = resolveItem(child, childStart);
            children.add(resolved);
            siblingOffset.put(upper(child.name()), childStart);
            if (child.redefines().isEmpty()) {
                cursor += resolved.totalSpan();
            }
        }
        int groupLength = cursor - startOffset;
        return new LayoutField(item.name(), item.level(), startOffset, groupLength, occurs,
                Optional.empty(), item.redefines(), item.conditionNames(), children);
    }

    private static String upper(String name) {
        return name.toUpperCase(java.util.Locale.ROOT);
    }
}
