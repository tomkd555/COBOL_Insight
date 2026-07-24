package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.engineapi.linemap.LineMappingEntry;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.transpile.GeneratedFile;
import jp.cobolinsight.engineapi.transpile.TargetLanguage;
import jp.cobolinsight.engineapi.transpile.TranspileResult;
import jp.cobolinsight.transpile.LayoutField;
import jp.cobolinsight.transpile.RecordLayoutResolver;
import jp.cobolinsight.transpile.proc.DataDivisionSql;
import jp.cobolinsight.transpile.proc.ProcedureDialect;
import jp.cobolinsight.transpile.proc.ProcedureIr;
import jp.cobolinsight.transpile.proc.ProcedureModelBuilder;
import jp.cobolinsight.transpile.proc.ProcedureRenderer;
import jp.cobolinsight.transpile.proc.ProgramSymbols;
import jp.cobolinsight.transpile.proc.SourceSlicer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意味モデルを対象言語のレコード/アクセサ群と手続き部の対訳コードへ逐語対訳する入口。ランタイムヘルパ1ファイル、
 * 独立レベル(01・77)の各レコードにつき1クラスファイル、手続き部を持つプログラムでは段落=メソッドの
 * プログラムファイル1つを生成し、宣言行・文行→生成行の対応表を安定順で組む。
 * 決定論: 生成順は意味モデルの並び順、行対応は安定キー整列、改行は LF・BOMなし。
 * 手続き対訳は GO TO を含まない構造化 COBOL を対象とし、原ソース {@code sourceText} を渡すと
 * inline PERFORM VARYING の反復変数句を復元する(渡さない場合は該当句を省略する)。
 */
public final class Transpiler {

    private Transpiler() {
    }

    public static TranspileResult transpile(CobolSemanticModel model, TargetLanguage language) {
        return transpile(model, null, language);
    }

    public static TranspileResult transpile(CobolSemanticModel model, String sourceText,
            TargetLanguage language) {
        LanguageEmitter emitter = LanguageEmitter.of(language);
        String programId = model.programId();
        List<GeneratedFile> files = new ArrayList<>();
        List<PendingMapping> pending = new ArrayList<>();

        files.add(emitter.runtimeLibrary());

        for (DataItem item : model.dataItems()) {
            if (item.level() != 1 && item.level() != 77) {
                continue;
            }
            LayoutField layout = RecordLayoutResolver.resolve(item);
            String fileName = emitter.recordFileName(item.name());
            LineTrackingEmitter out = new LineTrackingEmitter(emitter.indentUnit());
            RecordClassGenerator.generate(programId, fileName, item, layout, emitter, out);
            files.add(new GeneratedFile(fileName, out.render()));
            pending.addAll(out.mappings());
        }

        if (!model.procedures().isEmpty()) {
            ProgramSymbols symbols = ProgramSymbols.build(model.dataItems());
            SourceSlicer slicer = sourceText == null ? null : new SourceSlicer(sourceText);
            Map<SourceRange, EmbeddedBlock> embeddedByRange = new LinkedHashMap<>();
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                embeddedByRange.putIfAbsent(block.range(), block);
            }
            List<ProcedureIr> procedures =
                    new ProcedureModelBuilder(symbols, embeddedByRange, slicer)
                            .build(model.procedures());
            List<DataDivisionSql.Directive> dataSql = DataDivisionSql.extract(sourceText);
            ProcedureDialect dialect = ProcedureDialect.of(language);
            String programFile = dialect.programFileName(programId);
            String programSourceId = sourceId(model.sourceFile());
            LineTrackingEmitter out = new LineTrackingEmitter(dialect.indentUnit());
            ProcedureRenderer.render(out, programFile, programId, programSourceId, symbols,
                    procedures, dataSql, dialect);
            files.add(new GeneratedFile(programFile, out.render()));
            pending.addAll(out.mappings());
        }

        List<LineMappingEntry> lineMap = LineMapAssembler.assemble(programId, pending);
        return new TranspileResult(programId, language, files, lineMap);
    }

    /** パス末尾のファイル名を宣言元ソースの識別子とする。 */
    private static String sourceId(String file) {
        int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return separator >= 0 ? file.substring(separator + 1) : file;
    }
}
