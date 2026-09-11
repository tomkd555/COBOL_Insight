package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.transpile.emit.FieldKind;
import jp.cobolinsight.transpile.emit.Identifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Symbol table for the procedure translation. Assigns each elementary item a flat field on the
 * generated side (numeric = long/integer, alphanumeric = string, OCCURS = array), so that 88-level
 * condition names can be expanded into a comparison against the parent item's value. Group items are
 * not turned into fields; they are referenced by name only in CALL arguments and group MOVEs. Names
 * are normalized to match COBOL's case-insensitivity by uppercasing ASCII (Japanese text is left as
 * is). Field-name uniquification is done deterministically in declaration order.
 */
public final class ProgramSymbols {

    /** Field assignment for an elementary item. occursCounts lists OCCURS counts from outermost to innermost (empty means scalar). */
    public record DataSymbol(String cobolName, String fieldName, boolean isString,
            List<Integer> occursCounts, Optional<String> valueClause) {
        public DataSymbol {
            occursCounts = List.copyOf(occursCounts);
        }
    }

    /** An 88-level condition name. Determined by equality with the parent elementary item's value. */
    public record ConditionSymbol(String cobolName, DataSymbol parent, List<String> values) {
        public ConditionSymbol {
            values = List.copyOf(values);
        }
    }

    private final List<DataSymbol> declarations;
    private final Map<String, DataSymbol> byName;
    private final Map<String, ConditionSymbol> conditions;

    private ProgramSymbols(List<DataSymbol> declarations, Map<String, DataSymbol> byName,
            Map<String, ConditionSymbol> conditions) {
        this.declarations = declarations;
        this.byName = byName;
        this.conditions = conditions;
    }

    public static ProgramSymbols build(List<DataItem> dataItems) {
        Builder builder = new Builder();
        for (DataItem item : dataItems) {
            builder.walk(item, List.of());
        }
        return new ProgramSymbols(List.copyOf(builder.declarations), builder.byName,
                builder.conditions);
    }

    /** Elementary items in declaration order (used for field declarations). */
    public List<DataSymbol> declarations() {
        return declarations;
    }

    public Optional<DataSymbol> field(String cobolName) {
        return Optional.ofNullable(byName.get(normalize(cobolName)));
    }

    public Optional<ConditionSymbol> condition(String cobolName) {
        return Optional.ofNullable(conditions.get(normalize(cobolName)));
    }

    static String normalize(String cobolName) {
        return cobolName.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Builder {
        private final List<DataSymbol> declarations = new ArrayList<>();
        private final Map<String, DataSymbol> byName = new LinkedHashMap<>();
        private final Map<String, ConditionSymbol> conditions = new LinkedHashMap<>();
        private final Set<String> usedFieldNames = new java.util.HashSet<>();

        void walk(DataItem item, List<Integer> occursStack) {
            List<Integer> here = occursStack;
            if (item.occurs().isPresent()) {
                here = new ArrayList<>(occursStack);
                here.add(item.occurs().get().maxTimes());
            }
            if (item.picture().isPresent()) {
                DataSymbol symbol = leaf(item, here);
                declarations.add(symbol);
                byName.putIfAbsent(normalize(item.name()), symbol);
                for (ConditionName cond : item.conditionNames()) {
                    conditions.putIfAbsent(normalize(cond.name()),
                            new ConditionSymbol(cond.name(), symbol, cond.values()));
                }
            } else {
                for (DataItem child : item.children()) {
                    walk(child, here);
                }
            }
        }

        private DataSymbol leaf(DataItem item, List<Integer> occursCounts) {
            PictureType type = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            boolean isString = FieldKind.of(type) == FieldKind.ALPHANUMERIC;
            String field = uniqueField(item.name());
            return new DataSymbol(item.name(), field, isString, occursCounts, item.value());
        }

        /**
         * COBOL allows items with the same name under different groups, so normalized names can
         * collide. Appends {@code _2}, {@code _3}, … in declaration order to make them unique.
         */
        private String uniqueField(String cobolName) {
            String base = Identifiers.sanitize(cobolName);
            String candidate = base;
            int suffix = 2;
            while (!usedFieldNames.add(candidate)) {
                candidate = base + "_" + suffix;
                suffix++;
            }
            return candidate;
        }
    }
}
