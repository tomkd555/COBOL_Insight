package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.SourceTextIndex;

import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Derives the cross-program view the rules see from what the frontends parsed: the decoded text
 * index, and the SQL statement models behind the {@code EXEC SQL} blocks.
 *
 * <p>Both are gated on {@code needs}: a run whose rules never look at source text or SQL does not
 * pay for building either.
 *
 * <p>A block the SQL grammar will not take in full is kept all the same, in degraded form — its
 * kind, its tables and its cursor name are still worth knowing — and reported as a warning at its
 * own line, so nobody has to guess which statement the tool read only in part. A block that came
 * in through a COPY or an SQL INCLUDE is reported against the member it stands in, once, however
 * many programs copy it.
 *
 * <p>An SQL script's statements are split and read in {@link Parse}, where the script is the unit
 * being parsed; what is left here is the same warning for the ones the grammar read only in part.
 */
public final class Semantic implements Step {

    @Override
    public void apply(SourceSet s) {
        if (s.requires(Needs.SOURCE_TEXT)) {
            Map<String, String> textByPath = new LinkedHashMap<>();
            for (DecodedSource decoded : s.decoded().values()) {
                textByPath.put(decoded.path(), decoded.text());
            }
            s.artifact(SourceTextIndex.class, new SourceTextIndex(textByPath));
        }
        if (!s.requires(Needs.SQL)) {
            return;
        }
        SqlParser sqlParser = EngineWiring.sqlParser();
        Map<String, String> relPathByFile = relPathByFile(s);
        Set<String> reported = new HashSet<>();
        for (Map.Entry<String, CobolSemanticModel> entry : s.programsByPath().entrySet()) {
            for (EmbeddedBlock block : entry.getValue().embeddedBlocks()) {
                if (block.kind() != EmbeddedBlockKind.SQL) {
                    continue;
                }
                ParseOutcome<SqlStatementModel> outcome = sqlParser.parse(block);
                if (outcome instanceof ParseOutcome.Failure<SqlStatementModel> failure) {
                    s.addFinding(entry.getKey(), failure.finding());
                    continue;
                }
                SqlStatementModel statement = outcome.value().orElseThrow();
                if (!statement.isFullyAnalysed()) {
                    // The statement may stand in a copybook rather than in the program that copies
                    // it: file the warning against the source its own position names, and only the
                    // first time that line is seen.
                    String owner = ownerOf(relPathByFile, statement.range().start().file(),
                            entry.getKey());
                    // Keyed by the column as well, so two blocks written on one line each get
                    // their own warning.
                    if (reported.add(owner + ":" + statement.range().start().line()
                            + ":" + statement.range().start().column())) {
                        s.addFinding(owner, degraded(statement));
                    }
                }
                s.sqlByPath().computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(statement);
            }
        }
        reportDegradedScriptStatements(s);
    }

    /**
     * The same warning for the statements of an SQL script, which {@link Parse} has already read.
     * A script is its own owner, so there is nothing to attribute and nothing to keep apart.
     */
    private static void reportDegradedScriptStatements(SourceSet s) {
        s.sqlByScript().forEach((relPath, statements) -> {
            for (SqlStatementModel statement : statements) {
                if (!statement.isFullyAnalysed()) {
                    s.addFinding(relPath, degraded(statement));
                }
            }
        });
    }

    /** What a statement the grammar read only in part is reported as. */
    private static Finding degraded(SqlStatementModel statement) {
        return Finding.of(Finding.SQL_SYNTAX_RULE_ID, FindingLevel.WARNING,
                "SQL 文を完全には解析できませんでした（"
                        + statement.diagnostic().orElse("理由は不明です")
                        + "）。文の種別と表名だけを使います。",
                statement.range().start());
    }

    /**
     * The unit the statement's own position names, or the program that pulled the block in when
     * the position names no file of this walk — Che4z hands back its own URI for the code it
     * inserts itself, and that is not a path at all.
     */
    private static String ownerOf(Map<String, String> relPathByFile, String file, String program) {
        try {
            return relPathByFile.getOrDefault(Paths.normalisedKey(file), program);
        } catch (InvalidPathException e) {
            return program;
        }
    }

    /** Every unit of this walk by the absolute path a frontend would put in a position. */
    private static Map<String, String> relPathByFile(SourceSet s) {
        Map<String, String> byFile = new HashMap<>();
        for (SourceUnit unit : s.units()) {
            byFile.put(Paths.normalisedKey(unit.absPath().toString()), unit.relPath());
        }
        return byFile;
    }
}
