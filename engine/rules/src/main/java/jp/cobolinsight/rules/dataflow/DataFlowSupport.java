package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.ConditionName;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.Occurs;
import jp.cobolinsight.rules.SourceTextIndex;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * データフロー段ルールが共有するデータ項目リゾルバ。1プログラム分の意味モデルと原ソース索引から、
 * データ名の PICTURE(符号・桁)・OCCURS 上限・宣言節・88レベルの親項目・PROCEDURE DIVISION USING
 * 引数を解決する。解決はまず意味モデル {@link DataItem} を引き、必要な字句情報(宣言節・COPY 文)は
 * {@link SourceTextIndex} から補う(R017 のコピー句解決を踏襲)。状態を1プログラムに閉じて持つ。
 */
final class DataFlowSupport {

    /** データ項目の宣言節。R001 の照会対象限定と R005 のリンケージ表除外に使う。 */
    enum Section {
        FILE, WORKING_STORAGE, LOCAL_STORAGE, LINKAGE, UNKNOWN
    }

    /** 表参照 {@code TABLE(subscript, ...)}。subscripts は括弧内の各添字トークン(原表記)。 */
    record TableRef(String tableName, List<String> subscripts) {
    }

    private static final String NAME_CHARS = "\\p{L}\\p{N}$#_-";
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)\\b(FILE|WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\\s+SECTION\\b");
    private static final Pattern USING_CLAUSE = Pattern.compile(
            "(?is)\\bPROCEDURE\\s+DIVISION\\b(.*?)\\.");
    private static final Pattern NAME_TOKEN = Pattern.compile("[" + NAME_CHARS + "]*\\p{L}[" + NAME_CHARS + "]*");
    private static final Set<String> SPECIAL_REGISTERS =
            Set.of("SQLCODE", "SQLSTATE", "RETURN-CODE", "SQLCA", "WHEN-COMPILED");

    private final CobolSemanticModel model;
    private final SourceTextIndex texts;
    private final Map<String, DataItem> itemByName = new LinkedHashMap<>();
    private final Map<String, Integer> occursMaxByName = new LinkedHashMap<>();
    private final Map<String, String> parentByConditionName = new LinkedHashMap<>();
    private final Set<String> externallyInitialized = new LinkedHashSet<>();
    private final Map<String, Section> sectionCache = new LinkedHashMap<>();

    DataFlowSupport(CobolSemanticModel model, SourceTextIndex texts) {
        this.model = model;
        this.texts = texts;
        for (DataItem item : model.dataItems()) {
            index(item, null);
        }
        resolveUsingParameters();
    }

    static String norm(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT);
        int paren = n.indexOf('(');
        return paren >= 0 ? n.substring(0, paren).trim() : n;
    }

    // ---- データ項目索引 ----

    private void index(DataItem item, Integer inheritedOccurs) {
        String name = norm(item.name());
        itemByName.putIfAbsent(name, item);
        Integer occurs = item.occurs().map(o -> o.maxTimes()).orElse(inheritedOccurs);
        if (occurs != null) {
            occursMaxByName.putIfAbsent(name, occurs);
        }
        for (ConditionName cn : item.conditionNames()) {
            parentByConditionName.putIfAbsent(norm(cn.name()), name);
        }
        for (DataItem child : item.children()) {
            index(child, occurs);
        }
    }

    private void resolveUsingParameters() {
        String source = texts.textOf(model.sourceFile()).orElse(null);
        if (source == null) {
            return;
        }
        Matcher m = USING_CLAUSE.matcher(source);
        if (!m.find()) {
            return;
        }
        String header = m.group(1);
        int using = header.toUpperCase(Locale.ROOT).indexOf("USING");
        if (using < 0) {
            return;
        }
        Matcher tokens = NAME_TOKEN.matcher(header.substring(using + "USING".length()));
        while (tokens.find()) {
            String tok = norm(tokens.group());
            if (tok.equals("BY") || tok.equals("REFERENCE") || tok.equals("CONTENT")
                    || tok.equals("VALUE") || tok.equals("RETURNING")) {
                continue;
            }
            addWithDescendants(tok);
        }
    }

    private void addWithDescendants(String name) {
        DataItem item = itemByName.get(name);
        if (item == null) {
            externallyInitialized.add(name);
            return;
        }
        Deque<DataItem> stack = new ArrayDeque<>();
        stack.push(item);
        while (!stack.isEmpty()) {
            DataItem cur = stack.pop();
            externallyInitialized.add(norm(cur.name()));
            for (DataItem child : cur.children()) {
                stack.push(child);
            }
        }
    }

    // ---- 問い合わせ ----

    Optional<DataItem> item(String name) {
        return Optional.ofNullable(itemByName.get(norm(name)));
    }

    boolean isDeclared(String name) {
        return itemByName.containsKey(norm(name));
    }

    boolean isGroupItem(String name) {
        DataItem item = itemByName.get(norm(name));
        return item != null && !item.children().isEmpty();
    }

    boolean isValueless(String name) {
        DataItem item = itemByName.get(norm(name));
        return item != null && item.value().isEmpty();
    }

    boolean isExternallyInitialized(String name) {
        return externallyInitialized.contains(norm(name));
    }

    boolean isSpecialRegister(String name) {
        return SPECIAL_REGISTERS.contains(norm(name));
    }

    Optional<String> conditionParent(String name) {
        return Optional.ofNullable(parentByConditionName.get(norm(name)));
    }

    Optional<Integer> occursMax(String name) {
        return Optional.ofNullable(occursMaxByName.get(norm(name)));
    }

    /** PICTURE を解析した型。意味モデルの picture/usage が空なら empty。 */
    Optional<PictureType> pictureType(String name) {
        DataItem item = itemByName.get(norm(name));
        if (item == null || item.picture().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PictureType.parse(item.picture().get(), item.usage().orElse(null)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 数字項目かつ符号(S)なしの受信項目か(R028 の判定対象)。桁数・型が読めなければ false。 */
    boolean isUnsignedNumeric(String name) {
        return pictureType(name).filter(PictureType::isNumeric).map(pt -> !pt.signed()).orElse(false);
    }

    /** 名前で引いたデータ項目の格納バイト長(R015/R016 用)。解決できなければ empty。 */
    Optional<Integer> byteLength(String name) {
        return item(name).flatMap(this::byteLength);
    }

    /**
     * データ項目の格納バイト長。基本項目は PICTURE+USAGE から、集団項目は配下基本項目の総和から
     * 求める。配下の OCCURS は反復回数を掛け、REDEFINES 項目は元項目に重なるため加算しない。
     * 配下に PICTURE を解決できない項目があれば empty。
     */
    Optional<Integer> byteLength(DataItem item) {
        if (item.picture().isPresent()) {
            try {
                return Optional.of(
                        PictureType.parse(item.picture().get(), item.usage().orElse(null)).byteLength());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (item.children().isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (DataItem child : item.children()) {
            if (child.redefines().isPresent()) {
                continue;
            }
            Optional<Integer> childLen = byteLength(child);
            if (childLen.isEmpty()) {
                return Optional.empty();
            }
            int times = child.occurs().map(Occurs::maxTimes).orElse(1);
            total += childLen.get() * times;
        }
        return Optional.of(total);
    }

    /** 宣言節。コピー句由来の項目は原プログラム内の COPY 文が属する節で判定する。 */
    Section sectionOf(String name) {
        String key = norm(name);
        Section cached = sectionCache.get(key);
        if (cached != null) {
            return cached;
        }
        Section resolved = resolveSection(key);
        sectionCache.put(key, resolved);
        return resolved;
    }

    private Section resolveSection(String key) {
        DataItem item = itemByName.get(key);
        if (item == null) {
            return Section.UNKNOWN;
        }
        String posFile = item.position().file();
        String declText = texts.textOf(posFile)
                .or(() -> texts.textOfBaseName(baseName(posFile))).orElse(null);
        if (declText != null) {
            Section direct = sectionBackward(declText, item.position().line());
            if (direct != Section.UNKNOWN) {
                return direct;
            }
        }
        // コピー句由来: 原プログラム内の該当 COPY 文の節で判定する。
        String programText = texts.textOf(model.sourceFile()).orElse(null);
        if (programText == null) {
            return Section.UNKNOWN;
        }
        int copyLine = copyLineOf(programText, baseName(posFile));
        if (copyLine > 0) {
            return sectionBackward(programText, copyLine);
        }
        return Section.UNKNOWN;
    }

    /** src の1始まり line 以前で最も近い節見出しの節。無ければ UNKNOWN。 */
    private static Section sectionBackward(String src, int line) {
        String[] lines = src.split("\n", -1);
        int idx = Math.min(line, lines.length) - 1;
        for (int i = idx; i >= 0; i--) {
            Matcher m = SECTION_HEADER.matcher(lines[i]);
            if (m.find()) {
                return switch (m.group(1).toUpperCase(Locale.ROOT)) {
                    case "FILE" -> Section.FILE;
                    case "WORKING-STORAGE" -> Section.WORKING_STORAGE;
                    case "LOCAL-STORAGE" -> Section.LOCAL_STORAGE;
                    case "LINKAGE" -> Section.LINKAGE;
                    default -> Section.UNKNOWN;
                };
            }
        }
        return Section.UNKNOWN;
    }

    /** src 内で COPY <base> を記す最初の行(1始まり)。無ければ 0。 */
    private static int copyLineOf(String src, String base) {
        Pattern copy = Pattern.compile(
                "(?i)\\bCOPY\\s+" + Pattern.quote(base) + "(?![" + NAME_CHARS + "])");
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (copy.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String baseName(String path) {
        Path fileName = Path.of(path).getFileName();
        String name = fileName == null ? path : fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ---- 表参照の抽出 ----

    /**
     * 文テキスト中の表参照 {@code TABLE(subscript, ...)} を抽出する。文字列リテラルは除外し、
     * 部分参照(コロンを含む {@code NAME(a:b)})は添字ではないため除外する。
     */
    List<TableRef> tableRefs(String text) {
        String masked = maskLiterals(text);
        List<TableRef> refs = new ArrayList<>();
        Matcher head = Pattern.compile("([" + NAME_CHARS + "]*\\p{L}[" + NAME_CHARS + "]*)\\s*\\(")
                .matcher(masked);
        while (head.find()) {
            int open = head.end() - 1;
            int close = matchParen(masked, open);
            if (close < 0) {
                continue;
            }
            String inside = masked.substring(open + 1, close);
            if (inside.indexOf(':') >= 0) {
                continue; // 部分参照
            }
            List<String> subs = new ArrayList<>();
            for (String part : inside.split(",")) {
                String tok = part.trim();
                if (!tok.isEmpty()) {
                    subs.add(tok);
                }
            }
            if (!subs.isEmpty()) {
                refs.add(new TableRef(head.group(1), subs));
            }
        }
        return refs;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String maskLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
