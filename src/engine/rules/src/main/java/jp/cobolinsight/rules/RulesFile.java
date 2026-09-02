package jp.cobolinsight.rules;

import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.json.JsonReader;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.custom.CustomRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code rules.json}: per-rule overrides and custom rule definitions in one file.
 *
 * <pre>{@code
 * { "version": 2,
 *   "rules":  { "R008": {"enabled": false}, "R017": {"severity": "MEDIUM"} },
 *   "custom": [ { "id": "U001", ... } ] }
 * }</pre>
 *
 * <p>Failures are split in two. A file that is not JSON, or that carries a version this build
 * cannot read, means the whole configuration the author intended is not in effect, so it is
 * rejected outright. A single malformed entry is not: that entry is dropped, the reason lands in
 * {@link #errors()}, and the rest of the file still applies.
 *
 * <p>A missing file is not a failure at all. The GUI passes the same path whether or not the file
 * has been created yet.
 */
public record RulesFile(Map<String, RuleOverride> overrides, List<Rule> custom,
        List<String> errors) {

    /** The file format version this build reads. Raise it when the shape changes. */
    public static final long SUPPORTED_VERSION = 2L;

    /** A per-rule override. A null field means "not overridden". */
    public record RuleOverride(Boolean enabled, Severity severity) {
    }

    public RulesFile {
        overrides = Map.copyOf(overrides);
        custom = List.copyOf(custom);
        errors = List.copyOf(errors);
    }

    public static RulesFile empty() {
        return new RulesFile(Map.of(), List.of(), List.of());
    }

    /** Reads the file. A path of null, or a file that does not exist yet, yields the defaults. */
    public static RulesFile load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return empty();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " を読み込めませんでした");
        }
        return parse(json, file.toString());
    }

    /** Reads JSON text. {@code sourceLabel} names the origin in every reported problem. */
    public static RulesFile parse(String json, String sourceLabel) {
        Map<String, Object> root;
        try {
            root = JsonReader.asObject(JsonReader.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceLabel + " を JSON として解釈できません。"
                    + e.getMessage());
        }
        Object version = root.get("version");
        if (!Long.valueOf(SUPPORTED_VERSION).equals(version)) {
            throw new IllegalArgumentException(sourceLabel + ": version は " + SUPPORTED_VERSION
                    + " のみ扱えます（指定値 " + version + "）");
        }
        List<String> errors = new ArrayList<>();
        return new RulesFile(readOverrides(root.get("rules"), sourceLabel, errors),
                readCustom(root.get("custom"), sourceLabel, errors), errors);
    }

    private static Map<String, RuleOverride> readOverrides(Object value, String sourceLabel,
            List<String> errors) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> entries;
        try {
            entries = JsonReader.asObject(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceLabel + ": rules はオブジェクトで書いてください");
        }
        Map<String, RuleOverride> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            String where = sourceLabel + " の rules." + entry.getKey();
            try {
                overrides.put(entry.getKey(), toOverride(JsonReader.asObject(entry.getValue())));
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage() + "。この指定は無視します。");
            }
        }
        return overrides;
    }

    private static RuleOverride toOverride(Map<String, Object> object) {
        Object enabled = object.get("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            throw new IllegalArgumentException("enabled は true か false で書いてください");
        }
        Object severity = object.get("severity");
        if (severity != null && !(severity instanceof String)) {
            throw new IllegalArgumentException("severity は文字列で書いてください");
        }
        Severity parsed = null;
        if (severity != null) {
            try {
                parsed = Severity.valueOf(((String) severity).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("severity に扱えない値があります: " + severity
                        + "（扱えるのは HIGH・MEDIUM・LOW・ADVISORY）");
            }
        }
        return new RuleOverride((Boolean) enabled, parsed);
    }

    private static List<Rule> readCustom(Object value, String sourceLabel, List<String> errors) {
        if (value == null) {
            return List.of();
        }
        List<Object> elements;
        try {
            elements = JsonReader.asArray(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceLabel + ": custom は配列で書いてください");
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int index = 0; index < elements.size(); index++) {
            String where = sourceLabel + " の custom[" + index + "]";
            try {
                Rule rule = CustomRules.of(JsonReader.asObject(elements.get(index)));
                if (!seenIds.add(rule.meta().id())) {
                    errors.add(where + ": ID " + rule.meta().id()
                            + " は他の利用者定義ルールと重複しています。このルールは読み込みません。");
                    continue;
                }
                rules.add(rule);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage() + "。このルールは読み込みません。");
            }
        }
        return rules;
    }
}
