package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.ConditionName;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.rules.SourceTextIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R002 未使用データ項目。WORKING-STORAGE SECTIONまたはLOCAL-STORAGE SECTIONで宣言され、
 * PROCEDURE DIVISION内のいずれの文からも参照されないデータ項目を検出する。LINKAGE SECTION・
 * FILE SECTIONの項目は対象外とする。項目の所属セクションは原ソーステキスト(SourceTextIndex)の
 * セクション見出し行から判定し、コピー句由来の項目はCOPY文の出現セクションで判定する。
 * 集団項目の名前が参照される場合はその子孫を、子孫の名前が参照される場合はその祖先を、
 * いずれも使用済みとみなす。誤検出の抑止として次の2点を対象外とする:
 * (1) ENVIRONMENT DIVISION内(SELECT文のFILE STATUS句など)で名前が参照される項目、
 * (2) 88レベル条件名を宣言する項目(条件名を対で定義する慣用があり、samples/期待結果.md 8.2は
 * これを欠陥としない)。
 */
public final class UnusedDataItemRule implements Rule {

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^(FILE|WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\\s+SECTION\\s*\\.");
    private static final Pattern PROCEDURE_DIVISION = Pattern.compile(
            "(?i)^PROCEDURE\\s+DIVISION\\b");
    private static final Pattern COPY_NAME = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])COPY\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)");

    @Override
    public String id() {
        return "R002";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.LOW;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex index = context.artifact(SourceTextIndex.class).orElse(null);
        if (index == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String text = index.textOf(model.sourceFile()).orElse(null);
            if (text == null) {
                continue;
            }
            SectionLayout layout = SectionLayout.parse(text);
            Set<String> referenced = referencedNames(model);
            referenced.addAll(environmentDivisionTokens(text));
            propagateRedefines(model.dataItems(), referenced);
            for (DataItem top : model.dataItems()) {
                String section = sectionOf(top, model, layout);
                if (!"WORKING-STORAGE".equals(section) && !"LOCAL-STORAGE".equals(section)) {
                    continue;
                }
                report(top, section, referenced, findings);
            }
        }
        return findings;
    }

    /** 手続き部の全文テキストから参照名トークンを集める。 */
    private static Set<String> referencedNames(CobolSemanticModel model) {
        Set<String> referenced = new HashSet<>();
        Statements.walk(model, statement -> {
            for (String text : Statements.ownTexts(statement)) {
                referenced.addAll(CobolTexts.tokens(text));
            }
        });
        return referenced;
    }

    /** ENVIRONMENT DIVISION内の参照名トークン(SELECT文のFILE STATUS句などが該当する)。 */
    private static Set<String> environmentDivisionTokens(String text) {
        Set<String> tokens = new HashSet<>();
        boolean inEnvironment = false;
        for (String line : text.split("\n", -1)) {
            String raw = line.replace("\r", "");
            if (CobolTexts.isCommentLine(raw)) {
                continue;
            }
            String body = raw.length() > 7 ? raw.substring(7).trim() : raw.trim();
            if (body.toUpperCase(java.util.Locale.ROOT).matches("^ENVIRONMENT\\s+DIVISION\\b.*")) {
                inEnvironment = true;
                continue;
            }
            if (body.toUpperCase(java.util.Locale.ROOT).matches("^DATA\\s+DIVISION\\b.*")) {
                break;
            }
            if (inEnvironment) {
                tokens.addAll(CobolTexts.tokens(body));
            }
        }
        return tokens;
    }

    /** REDEFINES の再定義元・再定義先は記憶域を共有するため、片方の参照で双方を使用済み扱いにする。 */
    private static void propagateRedefines(List<DataItem> items, Set<String> referenced) {
        List<DataItem> flat = new ArrayList<>();
        flatten(items, flat);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DataItem item : flat) {
                if (item.redefines().isEmpty()) {
                    continue;
                }
                String self = CobolTexts.upper(item.name());
                String target = CobolTexts.upper(item.redefines().orElseThrow());
                if (referenced.contains(self) && referenced.add(target)) {
                    changed = true;
                }
                if (referenced.contains(target) && referenced.add(self)) {
                    changed = true;
                }
            }
        }
    }

    private static void flatten(List<DataItem> items, List<DataItem> out) {
        for (DataItem item : items) {
            out.add(item);
            flatten(item.children(), out);
        }
    }

    /**
     * トップレベル項目の所属セクション。主ソース内の項目は宣言行のセクションで、コピー句由来の
     * 項目はそのコピー句を取り込むCOPY文の出現セクションで判定する。判定できない場合は null。
     */
    private static String sectionOf(DataItem top, CobolSemanticModel model, SectionLayout layout) {
        if (top.position().file().equals(model.sourceFile())) {
            return layout.sectionAtLine(top.position().line());
        }
        Path fileName = Path.of(top.position().file()).getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String base = CobolTexts.upper(dot < 0 ? name : name.substring(0, dot));
        return layout.copySection(base);
    }

    /**
     * 未使用の最上位項目だけを報告する。自身の名前(または88レベル条件名)が参照される項目は、
     * 子孫を含めて使用済みとみなし打ち切る。FILLERは名前で参照できないため自身は報告せず、
     * 子だけを判定する。
     */
    private static void report(DataItem item, String section, Set<String> referenced,
            List<Finding> findings) {
        if (isFiller(item)) {
            for (DataItem child : item.children()) {
                report(child, section, referenced, findings);
            }
            return;
        }
        if (!item.conditionNames().isEmpty()) {
            return;
        }
        if (selfReferenced(item, referenced)) {
            return;
        }
        if (anyDescendantReferenced(item, referenced)) {
            for (DataItem child : item.children()) {
                report(child, section, referenced, findings);
            }
            return;
        }
        findings.add(Finding.of("R002", Severity.LOW.toLevel(),
                section + " SECTIONで宣言されたデータ項目 " + item.name()
                        + " は、PROCEDURE DIVISION内のいずれの文からも参照されていない。",
                item.position()));
    }

    private static boolean isFiller(DataItem item) {
        return "FILLER".equalsIgnoreCase(item.name());
    }

    private static boolean selfReferenced(DataItem item, Set<String> referenced) {
        if (referenced.contains(CobolTexts.upper(item.name()))) {
            return true;
        }
        for (ConditionName condition : item.conditionNames()) {
            if (referenced.contains(CobolTexts.upper(condition.name()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyDescendantReferenced(DataItem item, Set<String> referenced) {
        for (DataItem child : item.children()) {
            if (selfReferenced(child, referenced) || anyDescendantReferenced(child, referenced)) {
                return true;
            }
        }
        return false;
    }

    /** 原ソーステキストのセクション見出しとCOPY文の位置づけ。 */
    private static final class SectionLayout {

        private final List<int[]> sectionStarts = new ArrayList<>(); // [行番号, セクション索引]
        private final List<String> sectionNames = new ArrayList<>();
        private final Map<String, String> sectionByCopyName = new HashMap<>();
        private final Set<String> ambiguousCopyNames = new HashSet<>();

        static SectionLayout parse(String text) {
            SectionLayout layout = new SectionLayout();
            String[] lines = text.split("\n", -1);
            String current = null;
            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i].replace("\r", "");
                if (CobolTexts.isCommentLine(raw)) {
                    continue;
                }
                String body = raw.length() > 7 ? raw.substring(7).trim() : raw.trim();
                Matcher header = SECTION_HEADER.matcher(body);
                if (header.find()) {
                    current = CobolTexts.upper(header.group(1));
                    layout.sectionNames.add(current);
                    layout.sectionStarts.add(new int[] {i + 1, layout.sectionNames.size() - 1});
                    continue;
                }
                if (PROCEDURE_DIVISION.matcher(body).find()) {
                    current = "PROCEDURE";
                    layout.sectionNames.add(current);
                    layout.sectionStarts.add(new int[] {i + 1, layout.sectionNames.size() - 1});
                    continue;
                }
                Matcher copy = COPY_NAME.matcher(CobolTexts.stripLiterals(body));
                while (copy.find() && current != null) {
                    String name = CobolTexts.upper(copy.group(1));
                    String previous = layout.sectionByCopyName.putIfAbsent(name, current);
                    if (previous != null && !previous.equals(current)) {
                        layout.ambiguousCopyNames.add(name);
                    }
                }
            }
            return layout;
        }

        /** 指定行が属するセクション名。セクション見出しより前の行は null。 */
        String sectionAtLine(int line) {
            String section = null;
            for (int[] start : sectionStarts) {
                if (start[0] <= line) {
                    section = sectionNames.get(start[1]);
                } else {
                    break;
                }
            }
            return section;
        }

        /** コピー句名(拡張子なし・大文字)→COPY文の出現セクション。曖昧な場合は null。 */
        String copySection(String copyBaseName) {
            if (ambiguousCopyNames.contains(copyBaseName)) {
                return null;
            }
            return sectionByCopyName.get(copyBaseName);
        }
    }
}
