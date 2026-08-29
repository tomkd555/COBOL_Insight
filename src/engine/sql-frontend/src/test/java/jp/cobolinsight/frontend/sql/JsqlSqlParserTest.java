package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.sql.HostVariableBinding;
import jp.cobolinsight.core.sql.SqlStatementModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** engine-api の SqlParser SPI への変換の検証。 */
class Db2zSqlParserTest {

    private final Db2zSqlParser parser = new Db2zSqlParser();

    private static EmbeddedBlock sqlBlock(String text) {
        SourcePosition start = new SourcePosition("SYK006.cbl", 100, 12, -1);
        SourcePosition end = new SourcePosition("SYK006.cbl", 103, 20, -1);
        return new EmbeddedBlock(EmbeddedBlockKind.SQL, text, Map.of(),
                new SourceRange(start, end));
    }

    @Test
    void selectIntoWithHyphenatedHostVariablesIsMapped() {
        EmbeddedBlock block = sqlBlock(
                "SELECT STK_QTY INTO :WS-STK-QTY FROM STOCK WHERE ITEM_CD = :WS-ITEM-CD");

        ParseOutcome<SqlStatementModel> outcome = parser.parse(block);

        SqlStatementModel model = outcome.value().orElseThrow();
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.SELECT, model.kind());
        assertEquals(block.text(), model.originalText());
        assertTrue(model.mangledText().contains(":HV1"));
        assertTrue(model.mangledText().contains(":HV2"));
        assertEquals(2, model.hostVariables().size());
        HostVariableBinding first = model.hostVariables().get(0);
        assertEquals("WS-STK-QTY", first.originalName());
        assertEquals("HV1", first.mangledName());
        assertTrue(first.indicatorName().isEmpty());
        assertEquals("WS-ITEM-CD", model.hostVariables().get(1).originalName());
        assertTrue(model.referencedTables().contains("STOCK"));
        assertEquals(block.range(), model.range());
    }

    @Test
    void indicatorVariableIsCarriedIntoTheBinding() {
        EmbeddedBlock block = sqlBlock(
                "UPDATE STOCK SET STK_QTY = :WS-QTY:WS-QTY-IND WHERE ITEM_CD = :WS-ITEM-CD");

        SqlStatementModel model = parser.parse(block).value().orElseThrow();

        HostVariableBinding first = model.hostVariables().get(0);
        assertEquals("WS-QTY", first.originalName());
        assertEquals("WS-QTY-IND", first.indicatorName().orElseThrow());
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.UPDATE, model.kind());
    }

    @Test
    void cursorStatementKindsAreMappedToEngineApiKinds() {
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.DECLARE_CURSOR,
                kindOf("DECLARE CUR1 CURSOR FOR SELECT ITEM_CD FROM STOCK"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.OPEN, kindOf("OPEN CUR1"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.FETCH,
                kindOf("FETCH CUR1 INTO :WS-ITEM-CD"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.CLOSE, kindOf("CLOSE CUR1"));
    }

    private jp.cobolinsight.core.sql.SqlStatementKind kindOf(String sql) {
        return parser.parse(sqlBlock(sql)).value().orElseThrow().kind();
    }

    @Test
    void execSqlWrapperIsStrippedBeforeAnalysis() {
        EmbeddedBlock block = sqlBlock("""
                EXEC SQL
                    SELECT STK_QTY INTO :WS-STK-QTY
                      FROM STOCK
                     WHERE ITEM_CD = :WS-ITEM-CD
                END-EXEC.""");

        SqlStatementModel model = parser.parse(block).value().orElseThrow();

        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.SELECT, model.kind());
        assertTrue(model.referencedTables().contains("STOCK"));
        assertEquals(block.text(), model.originalText(), "原文は抽出テキストのまま保持すること");
    }

    @Test
    void unparseableSqlBecomesAnErrorLevelParseFailure() {
        EmbeddedBlock block = sqlBlock("SELECT FROM WHERE");

        ParseOutcome<SqlStatementModel> outcome = parser.parse(block);

        var finding = outcome.failureFinding().orElseThrow();
        assertEquals(FindingLevel.ERROR, finding.level());
        assertEquals("SYK006.cbl", finding.location().file());
        assertEquals(100, finding.location().line());
    }

    @Test
    void structureSignalsAreCarriedIntoTheModel() {
        SqlStatementModel star = parser.parse(sqlBlock("SELECT * FROM STOCK")).value().orElseThrow();
        assertTrue(star.structureSignals().selectStar());

        SqlStatementModel cursor = parser.parse(sqlBlock(
                "DECLARE CUR1 CURSOR FOR SELECT ITEM_CD FROM STOCK FOR READ ONLY"))
                .value().orElseThrow();
        assertTrue(cursor.structureSignals().cursor().orElseThrow().forReadOnly());
        assertEquals("CUR1", cursor.structureSignals().cursor().orElseThrow().cursorName());
    }
}
