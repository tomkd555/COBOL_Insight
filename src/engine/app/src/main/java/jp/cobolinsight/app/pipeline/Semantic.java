package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives the cross-program view the rules see from what the frontends parsed: the decoded text
 * index, and the SQL statement models behind the {@code EXEC SQL} blocks.
 *
 * <p>Both are gated on {@code needs}: a run whose rules never look at source text or SQL does not
 * pay for building either.
 *
 * <p>A block that will not parse is set aside rather than reported. Advice on SQL has nothing to
 * say about a statement it could not read, and only {@link Persist} — which records the failure
 * against the source it belongs to — has a use for it.
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
        for (Map.Entry<String, CobolSemanticModel> entry : s.programsByPath().entrySet()) {
            for (EmbeddedBlock block : entry.getValue().embeddedBlocks()) {
                if (block.kind() != EmbeddedBlockKind.SQL) {
                    continue;
                }
                ParseOutcome<SqlStatementModel> outcome = sqlParser.parse(block);
                if (outcome instanceof ParseOutcome.Failure<SqlStatementModel> failure) {
                    s.sqlParseFailuresByPath()
                            .computeIfAbsent(entry.getKey(), key -> new ArrayList<Finding>())
                            .add(failure.finding());
                    continue;
                }
                s.sqlByPath().computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(outcome.value().orElseThrow());
            }
        }
    }
}
