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
import java.util.TreeSet;

/**
 * 手続き対訳のための記号表。基本項目ごとにフラットな生成側フィールド(数値=long/整数、英数字=文字列、
 * OCCURS=配列)を割り当て、88レベル条件名を親項目の値比較へ展開できるようにする。集団項目は
 * フィールド化せず、CALL 引数・集団 MOVE でのみ名前として参照する。名前は COBOL の大小無視に合わせ
 * ASCII を大文字化して正規化する(日本語はそのまま)。フィールド名の一意化は宣言順で決定論的に行う。
 */
public final class ProgramSymbols {

    /** 基本項目のフィールド割り当て。occursCounts は外側から内側の OCCURS 回数(空ならスカラ)。 */
    public record DataSymbol(String cobolName, String fieldName, boolean isString,
            List<Integer> occursCounts, Optional<String> valueClause) {
        public DataSymbol {
            occursCounts = List.copyOf(occursCounts);
        }

        public boolean isArray() {
            return !occursCounts.isEmpty();
        }
    }

    /** 88レベル条件名。親基本項目の値と等しいかで判定する。 */
    public record ConditionSymbol(String cobolName, DataSymbol parent, List<String> values) {
        public ConditionSymbol {
            values = List.copyOf(values);
        }
    }

    private final List<DataSymbol> declarations;
    private final Map<String, DataSymbol> byName;
    private final Map<String, ConditionSymbol> conditions;
    private final Set<String> groupNames;

    private ProgramSymbols(List<DataSymbol> declarations, Map<String, DataSymbol> byName,
            Map<String, ConditionSymbol> conditions, Set<String> groupNames) {
        this.declarations = declarations;
        this.byName = byName;
        this.conditions = conditions;
        this.groupNames = groupNames;
    }

    public static ProgramSymbols build(List<DataItem> dataItems) {
        Builder builder = new Builder();
        for (DataItem item : dataItems) {
            builder.walk(item, List.of());
        }
        return new ProgramSymbols(List.copyOf(builder.declarations), builder.byName,
                builder.conditions, builder.groupNames);
    }

    /** 宣言順の基本項目一覧(フィールド宣言に用いる)。 */
    public List<DataSymbol> declarations() {
        return declarations;
    }

    public Optional<DataSymbol> field(String cobolName) {
        return Optional.ofNullable(byName.get(normalize(cobolName)));
    }

    public Optional<ConditionSymbol> condition(String cobolName) {
        return Optional.ofNullable(conditions.get(normalize(cobolName)));
    }

    public boolean isGroup(String cobolName) {
        return groupNames.contains(normalize(cobolName));
    }

    /** 基本項目・集団・88 のいずれかとして既知の名前か。 */
    public boolean isKnown(String cobolName) {
        String n = normalize(cobolName);
        return byName.containsKey(n) || groupNames.contains(n) || conditions.containsKey(n);
    }

    static String normalize(String cobolName) {
        return cobolName.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Builder {
        private final List<DataSymbol> declarations = new ArrayList<>();
        private final Map<String, DataSymbol> byName = new LinkedHashMap<>();
        private final Map<String, ConditionSymbol> conditions = new LinkedHashMap<>();
        private final Set<String> groupNames = new TreeSet<>();
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
                groupNames.add(normalize(item.name()));
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
         * COBOL は同じ名前の項目を別の集団の下に置けるため、正規化後の名前が衝突しうる。宣言順に
         * {@code _2}, {@code _3}, … を付けて一意にする。
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
