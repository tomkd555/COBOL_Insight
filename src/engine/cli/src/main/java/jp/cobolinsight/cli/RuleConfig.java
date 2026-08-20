package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.json.JsonReader;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ルールの有効・無効を書いた設定ファイル(JSON)。形式は次のとおりである。
 *
 * <pre>{"version": 1, "disabledRules": ["R001", "S002"]}</pre>
 *
 * <p>無効化の指定を engine の一箇所で読むための型である。設定ファイルと {@code --disable-rule}
 * のどちらから来ても、{@link #resolveDisabled} が和を取って各 Runner の
 * {@code disabledRuleIds} へ渡す。
 *
 * <p>誤りは 2 段階に分ける。ファイルが無い・JSON が壊れている・版数が違う場合は、利用者が
 * 意図した設定がまるごと効かないため誤りとして止める。配列の 1 要素だけが文字列でない、
 * といった部分的な誤りは残りの指定を捨てず、警告に回して解析を続ける。カタログに無いルールID
 * もここでは黙って受け入れる(照合はカタログを持つ {@link RulesRunner} が行う)。
 */
public record RuleConfig(Set<String> disabledRuleIds, List<String> warnings) {

    /** 扱える設定ファイルの版数。形式を変えるときに上げる。 */
    public static final long SUPPORTED_VERSION = 1L;

    private static final RuleConfig EMPTY = new RuleConfig(Set.of(), List.of());

    public RuleConfig {
        disabledRuleIds = Collections.unmodifiableSet(new LinkedHashSet<>(disabledRuleIds));
        warnings = List.copyOf(warnings);
    }

    /**
     * 設定ファイルを読む。指定が無い(null)場合は空の設定を返す。指定したファイルが無い場合は、
     * 綴り違いを黙って無視すると全ルールが有効のまま流れてしまうため誤りとする。
     */
    public static RuleConfig load(Path file) {
        if (file == null) {
            return EMPTY;
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(file + " が無い");
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " を読めない: " + e.getMessage());
        }
        return parse(json, file.toString());
    }

    /** JSON テキストから読む。sourceLabel は誤りの報告に添える設定の出所である。 */
    public static RuleConfig parse(String json, String sourceLabel) {
        Map<String, Object> root;
        try {
            root = JsonReader.asObject(JsonReader.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceLabel + ": " + e.getMessage());
        }
        Object version = root.get("version");
        if (version != null && !Long.valueOf(SUPPORTED_VERSION).equals(version)) {
            throw new IllegalArgumentException(sourceLabel + ": version は " + SUPPORTED_VERSION
                    + " のみ扱える(指定値 " + version + ")");
        }
        Object value = root.get("disabledRules");
        if (value == null) {
            return EMPTY;
        }
        List<Object> elements;
        try {
            elements = JsonReader.asArray(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceLabel + ": disabledRules は配列で書く");
        }
        Set<String> ids = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (int index = 0; index < elements.size(); index++) {
            Object element = elements.get(index);
            if (element instanceof String id && !id.isBlank()) {
                ids.add(id.strip());
            } else {
                warnings.add(sourceLabel + " の disabledRules[" + index
                        + "]: ルールIDは空でない文字列で書く(指定値 " + element + ")");
            }
        }
        return new RuleConfig(ids, warnings);
    }

    /**
     * 設定ファイルと {@code --disable-rule} の和を、無効化するルールIDの集合として返す。
     * {@code --disable-rule} を残してあるのは GUI がまだそちらを使うためである。
     */
    static Set<String> resolveDisabled(CommandLine.Model.CommandSpec spec, Path ruleConfigFile,
            List<String> disabledRuleOptions) {
        RuleConfig config;
        try {
            config = load(ruleConfigFile);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--rule-config: " + e.getMessage());
        }
        for (String warning : config.warnings()) {
            System.err.println("警告: ルール設定: " + warning);
        }
        Set<String> disabled = new LinkedHashSet<>(config.disabledRuleIds());
        disabled.addAll(disabledRuleOptions);
        return disabled;
    }
}
