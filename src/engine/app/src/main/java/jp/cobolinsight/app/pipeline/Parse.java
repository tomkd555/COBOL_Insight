package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.frontend.bms.BmsModelMapper;
import jp.cobolinsight.frontend.bms.BmsParseError;
import jp.cobolinsight.frontend.bms.BmsParseResult;
import jp.cobolinsight.frontend.bms.BmsSourceParser;

import java.nio.file.Path;
import java.util.List;

/**
 * Parses every decoded unit with the frontend for its kind. A file that fails to parse becomes a
 * finding and is recorded as unanalysable, and the run carries on with the rest — one unparsable
 * source must not cost the analysis of every other.
 */
public final class Parse implements Step {

    @Override
    public void apply(SourceSet s) {
        CobolParser cobol = EngineWiring.cobolParser();
        JclParser jcl = EngineWiring.jclParser();
        BmsSourceParser bms = EngineWiring.bmsParser();
        for (SourceUnit unit : s.units()) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded == null) {
                continue;
            }
            switch (unit.kind()) {
                case COBOL -> parseCobol(s, cobol, unit, decoded);
                case JCL -> parseJcl(s, jcl, unit, decoded);
                case BMS -> parseBms(s, bms, unit, decoded);
                case COPYBOOK -> {
                    // Copybooks are analysed through the programs that copy them; on their own they
                    // contribute only their text.
                }
            }
        }
    }

    private static void parseCobol(SourceSet s, CobolParser parser, SourceUnit unit,
            DecodedSource decoded) {
        ParseOutcome<CobolSemanticModel> outcome =
                parser.parse(decoded, s.options().copybookSearchPaths());
        if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
            s.addFinding(unit.relPath(), failure.finding());
            s.unanalyzable().put(unit.relPath(), failure.finding().message());
            return;
        }
        s.programsByPath().put(unit.relPath(), outcome.value().orElseThrow());
    }

    private static void parseJcl(SourceSet s, JclParser parser, SourceUnit unit,
            DecodedSource decoded) {
        ParseOutcome<JclJobModel> outcome = parser.parse(decoded, searchPathOf(unit));
        if (outcome instanceof ParseOutcome.Failure<JclJobModel> failure) {
            s.addFinding(unit.relPath(), failure.finding());
            s.unanalyzable().put(unit.relPath(), failure.finding().message());
            return;
        }
        s.jobsByPath().put(unit.relPath(), outcome.value().orElseThrow());
    }

    private static void parseBms(SourceSet s, BmsSourceParser parser, SourceUnit unit,
            DecodedSource decoded) {
        BmsParseResult result = parser.parse(decoded.text());
        for (BmsParseError error : result.errors()) {
            // BmsParseError columns are zero-based; SourcePosition columns are one-based.
            s.addFinding(unit.relPath(), Finding.parseFailure(
                    new SourcePosition(unit.relPath(), Math.max(1, error.line()),
                            error.column() + 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                    error.message()));
        }
        List<BmsMapset> mapsets = BmsModelMapper.toEngineApi(result, unit.relPath());
        s.mapsetsByPath().put(unit.relPath(), mapsets);
    }

    /**
     * Where a JCL's INCLUDE members are looked for: its own directory only. The walk takes assets
     * wherever they sit, so no fixed layout can be assumed.
     */
    private static List<Path> searchPathOf(SourceUnit unit) {
        Path parent = unit.absPath().getParent();
        return parent == null ? List.of() : List.of(parent);
    }
}
