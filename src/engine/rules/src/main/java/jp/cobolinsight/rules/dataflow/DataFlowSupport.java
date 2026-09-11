package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Occurs;
import jp.cobolinsight.rules.SourceTextIndex;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
 * Data item resolver shared by the dataflow analysis rules. From the semantic model and source
 * text index of a single program, it resolves a data name's PICTURE (sign/digits), OCCURS upper
 * bound, declaring section, the parent item of an 88-level condition name, and the PROCEDURE
 * DIVISION USING parameters. Resolution first looks up the semantic model {@link DataItem}, then
 * supplements any lexical information needed (declaring section, COPY statements) from
 * {@link SourceTextIndex} (the COPY clause resolution procedure is the same as R017). Holds state
 * scoped to a single program.
 */
final class DataFlowSupport {

    /** The declaring section of a data item. Used by R001 to restrict its query targets and by R005 to exclude LINKAGE tables. */
    enum Section {
        FILE, WORKING_STORAGE, LOCAL_STORAGE, LINKAGE, UNKNOWN
    }

    /** A table reference {@code TABLE(subscript, ...)}. subscripts are the individual subscript tokens inside the parentheses (verbatim). */
    record TableRef(String tableName, List<String> subscripts) {
    }

    private static final String NAME_CHARS = "\\p{L}\\p{N}$#_-";
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)\\b(FILE|WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\\s+SECTION\\b");
    private static final Pattern USING_CLAUSE = Pattern.compile(
            "(?is)\\bPROCEDURE\\s+DIVISION\\b(.*?)\\.");
    // Require at least one letter, matching the rule that a data name must contain at least one
    // letter. This keeps numeric literals from being picked up as names.
    private static final Pattern NAME_TOKEN = Pattern.compile("[" + NAME_CHARS + "]*\\p{L}[" + NAME_CHARS + "]*");
    private static final Set<String> SPECIAL_REGISTERS =
            Set.of("SQLCODE", "SQLSTATE", "RETURN-CODE", "SQLCA", "WHEN-COMPILED");

    private final CobolSemanticModel model;
    private final SourceTextIndex texts;
    private final Map<String, DataItem> itemByName = new LinkedHashMap<>();
    private final Map<String, Integer> occursMaxByName = new LinkedHashMap<>();
    private final Map<String, List<Integer>> occursDimsByName = new LinkedHashMap<>();
    private final Map<String, String> parentByConditionName = new LinkedHashMap<>();
    private final Set<String> externallyInitialized = new LinkedHashSet<>();
    private final Map<String, Section> sectionCache = new LinkedHashMap<>();

    DataFlowSupport(CobolSemanticModel model, SourceTextIndex texts) {
        this.model = model;
        this.texts = texts;
        for (DataItem item : model.dataItems()) {
            index(item, List.of());
        }
        resolveUsingParameters();
    }

    /**
     * Every dataflow rule builds its own instance for the program it is evaluating, so a program
     * with several such rules re-indexes the same data items once per rule. Memoised on the
     * model's identity (not {@code equals}, since {@link CobolSemanticModel} is a record whose
     * generated equality would walk the whole parsed program on every cache lookup) for the
     * lifetime of this process, which is one CLI invocation.
     */
    private static final Map<CobolSemanticModel, DataFlowSupport> CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    static DataFlowSupport of(CobolSemanticModel model, SourceTextIndex texts) {
        return CACHE.computeIfAbsent(model, m -> new DataFlowSupport(m, texts));
    }

    static String norm(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT);
        int paren = n.indexOf('(');
        return paren >= 0 ? n.substring(0, paren).trim() : n;
    }

    // ---- Data item index ----

    private void index(DataItem item, List<Integer> inheritedDims) {
        String name = norm(item.name());
        itemByName.putIfAbsent(name, item);
        List<Integer> dims = inheritedDims;
        if (item.occurs().isPresent()) {
            dims = new ArrayList<>(inheritedDims);
            dims.add(item.occurs().get().maxTimes());
        }
        if (!dims.isEmpty()) {
            occursMaxByName.putIfAbsent(name, dims.get(dims.size() - 1));
            occursDimsByName.putIfAbsent(name, List.copyOf(dims));
        }
        for (ConditionName cn : item.conditionNames()) {
            parentByConditionName.putIfAbsent(norm(cn.name()), name);
        }
        for (DataItem child : item.children()) {
            index(child, dims);
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

    // ---- Queries ----

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

    /**
     * Returns the OCCURS upper bound of each dimension of a table, ordered from the outermost
     * dimension. Since the subscripts of a multi-dimensional table are listed from the outermost
     * dimension, this ordering matches the subscript ordering. Empty if not a table.
     */
    List<Integer> occursDims(String name) {
        return occursDimsByName.getOrDefault(norm(name), List.of());
    }

    /** The type parsed from PICTURE. Empty if the semantic model's picture/usage is empty. */
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

    /** Whether this is a numeric item without a sign (S) as a receiving item (the target of R028's check). False if the digit count or type cannot be read. */
    boolean isUnsignedNumeric(String name) {
        return pictureType(name).filter(PictureType::isNumeric).map(pt -> !pt.signed()).orElse(false);
    }

    /** The storage byte length of the data item looked up by name (used by R015/R016). Empty if it cannot be resolved. */
    Optional<Integer> byteLength(String name) {
        return item(name).flatMap(this::byteLength);
    }

    /**
     * The storage byte length of a data item. For an elementary item this comes from
     * PICTURE+USAGE; for a group item it is the sum over its descendant elementary items.
     * A descendant's OCCURS is multiplied by its repetition count, and a REDEFINES item is not
     * added because it overlaps the original item. Empty if any descendant item's PICTURE cannot
     * be resolved.
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

    /** The declaring section. For an item that comes from a COPY clause, this is determined by the section containing the COPY statement in the original program. */
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
        // From a COPY clause: determine it by the section of the matching COPY statement in the
        // original program.
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

    /** The section of the nearest section header at or before the 1-based line in src. UNKNOWN if none. */
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

    /** The first line (1-based) in src that names COPY <base>. 0 if none. */
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

    // ---- Extracting table references ----

    /**
     * Extracts table references {@code TABLE(subscript, ...)} from statement text. String literals
     * are excluded, and a reference modification (a {@code NAME(a:b)} containing a colon) is
     * excluded because it is not a subscript.
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
                continue; // reference modification
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
