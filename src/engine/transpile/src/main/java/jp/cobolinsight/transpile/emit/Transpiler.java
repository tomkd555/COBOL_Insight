package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
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
 * The entry point that line-by-line translates a semantic model into a target language's records,
 * accessors, and procedure division translation code. Generates one runtime helper file, one class
 * file per independent-level (01/77) record, and, for a program with a procedure division, one
 * program file where each paragraph becomes a method, then assembles the declaration-line/
 * statement-line-to-generated-line correspondence table in a stable order.
 * Determinism: generation order follows the semantic model's order, line correspondences are sorted
 * by a stable key, and line breaks are LF with no BOM.
 * The procedure translation reduces GO TO to structured control; forms that cannot be reduced are
 * left untranslated with a note. Passing the original source {@code sourceText} restores the loop
 * variable clause of an inline PERFORM VARYING (omitted if not passed).
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

    /** Uses the trailing file name of the path as the declaring source's identifier. */
    private static String sourceId(String file) {
        int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return separator >= 0 ? file.substring(separator + 1) : file;
    }
}
