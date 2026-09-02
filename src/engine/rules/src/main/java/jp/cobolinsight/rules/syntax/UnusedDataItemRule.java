package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
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
 * R002 Unused data item. Detects a data item that is declared in the WORKING-STORAGE
 * SECTION or LOCAL-STORAGE SECTION but is not referenced by any statement in the
 * PROCEDURE DIVISION. Items in the LINKAGE SECTION and FILE SECTION are excluded. An
 * item's owning section is judged from the section-header lines of the original source
 * text (SourceTextIndex); an item that comes from a copybook is judged by the section in
 * which the COPY statement that pulls it in appears. When a group item's name is
 * referenced, its descendants are also treated as used; when a descendant's name is
 * referenced, its ancestors are also treated as used. To suppress false positives, the
 * following two cases are excluded: (1) an item whose name is referenced within the
 * ENVIRONMENT DIVISION (such as a SELECT statement's FILE STATUS clause), and (2) an item
 * that declares an 88-level condition name (since it is customary to declare an item and
 * its condition name as a pair and reference only the condition name in the procedure
 * division, the item's name never appearing is not a defect).
 */
public final class UnusedDataItemRule implements Rule {

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^(FILE|WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\\s+SECTION\\s*\\.");
    private static final Pattern PROCEDURE_DIVISION = Pattern.compile(
            "(?i)^PROCEDURE\\s+DIVISION\\b");
    private static final Pattern COPY_NAME = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])COPY\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)");

    private static final RuleMeta META = RuleMeta.named("R002", "未使用データ項目", "データフロー")
            .summary("作業場所節・局所記憶節で宣言され、"
                    + "手続き部のどの文からも参照されないデータ項目を検出する。")
            .rationale("使われない宣言は記憶域を占めるだけでなく、"
                    + "読む者に生きている項目と取り違えさせ、改修の判断を誤らせる。")
            .detection("集団項目は子孫の参照を、子孫は祖先の参照をもって使用済みとみなす。"
                    + "連絡節・ファイル節の項目、環境部に名前が現れる項目、"
                    + "条件名を宣言する項目は対象外とする。")
            .remedy("宣言を削る。将来の使用を見込んで残すなら、その理由を注記に書く。")
            .example("""
                    01  WS-WORK-AREA.
                        05  WS-TOTAL      PIC 9(7).
                        05  WS-OLD-TOTAL  PIC 9(7).
                    """, """
                    01  WS-WORK-AREA.
                        05  WS-TOTAL      PIC 9(7).
                    """)
            .severity(Severity.LOW)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL, AssetKind.COPYBOOK)
            .needs(Needs.SEMANTIC, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
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

    /** Collects reference-name tokens from the full text of every statement in the procedure division. */
    private static Set<String> referencedNames(CobolSemanticModel model) {
        Set<String> referenced = new HashSet<>();
        Statements.walk(model, statement -> {
            for (String text : Statements.ownTexts(statement)) {
                referenced.addAll(CobolTexts.tokens(text));
            }
        });
        return referenced;
    }

    /** Reference-name tokens within the ENVIRONMENT DIVISION (such as a SELECT statement's FILE STATUS clause). */
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

    /** A REDEFINES's redefined item and redefining item share storage, so a reference to either marks both as used. */
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
     * The owning section of a top-level item. An item in the main source is judged by the
     * section of its declaration line; an item that comes from a copybook is judged by the
     * section in which the COPY statement that pulls it in appears. Returns null when it
     * cannot be determined.
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
     * Reports only the topmost unused item. An item whose own name (or 88-level condition
     * name) is referenced is treated as used, including its descendants, and recursion
     * stops there. FILLER cannot be referenced by name, so it is never reported itself;
     * only its children are judged.
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
                section + " SECTION で宣言したデータ項目 " + item.name()
                        + " は、手続き部のどの文からも参照されない。",
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

    /** Section headers in the original source text and where COPY statements sit relative to them. */
    private static final class SectionLayout {

        private final List<int[]> sectionStarts = new ArrayList<>(); // [line number, section index]
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

        /** The name of the section a given line belongs to. A line before any section header returns null. */
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

        /** Maps a copybook name (no extension, uppercase) to the section its COPY statement appears in. Returns null when ambiguous. */
        String copySection(String copyBaseName) {
            if (ambiguousCopyNames.contains(copyBaseName)) {
                return null;
            }
            return sectionByCopyName.get(copyBaseName);
        }
    }
}
