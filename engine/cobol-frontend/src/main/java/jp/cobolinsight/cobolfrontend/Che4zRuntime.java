package jp.cobolinsight.cobolfrontend;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.eclipse.lsp.cobol.cli.di.CliModule;
import org.eclipse.lsp.cobol.common.AnalysisConfig;
import org.eclipse.lsp.cobol.common.AnalysisResult;
import org.eclipse.lsp.cobol.common.CleanerPreprocessor;
import org.eclipse.lsp.cobol.common.benchmark.BenchmarkService;
import org.eclipse.lsp.cobol.common.copybook.CopybookProcessingMode;
import org.eclipse.lsp.cobol.common.dialects.CobolLanguageId;
import org.eclipse.lsp.cobol.common.message.MessageService;
import org.eclipse.lsp.cobol.common.pipeline.Pipeline;
import org.eclipse.lsp.cobol.common.pipeline.PipelineResult;
import org.eclipse.lsp.cobol.core.engine.CobolLanguageEngine;
import org.eclipse.lsp.cobol.core.engine.analysis.AnalysisContext;
import org.eclipse.lsp.cobol.core.engine.dialects.DialectService;
import org.eclipse.lsp.cobol.core.preprocessor.delegates.GrammarPreprocessor;
import org.eclipse.lsp.cobol.dialects.TrueDialectServiceImpl;
import org.eclipse.lsp.cobol.dialects.ibm.CompilerDirectivesStage;
import org.eclipse.lsp.cobol.dialects.ibm.DialectCompilerDirectiveStage;
import org.eclipse.lsp.cobol.dialects.ibm.DialectProcessingStage;
import org.eclipse.lsp.cobol.dialects.ibm.IbmCleanupStage;
import org.eclipse.lsp.cobol.dialects.ibm.ImplicitDialectProcessingStage;
import org.eclipse.lsp.cobol.dialects.ibm.ParserStage;
import org.eclipse.lsp.cobol.dialects.ibm.ParserStageResult;
import org.eclipse.lsp.cobol.dialects.ibm.PreprocessorStage;
import org.eclipse.lsp.cobol.lsp.jrpc.CobolLanguageClient;
import org.eclipse.lsp.cobol.service.DocumentModelService;

import java.nio.file.Path;
import java.util.List;

/**
 * Che4z エンジンの Guice 起動と再利用。injector とエンジンを1度だけ構築し、
 * ファイルごとに run() と CST 取得パイプラインを実行する。
 *
 * <p>エンジンは文書を開いた状態を内部に持つため、解析は排他で行う。コピー句の探索パスも
 * 言語クライアントの共有状態であり、解析ごとに差し替える。
 */
final class Che4zRuntime {

    /** エンジンの AST 解析結果と、同じソースから取得した CST。cstCapture は取得に失敗した場合 null。 */
    record Analysis(AnalysisResult result, CstCapture cstCapture) {
    }

    private static Che4zRuntime instance;

    private final Injector injector;
    private final CobolLanguageEngine engine;
    private final DocumentModelService documentService;
    private final SearchPathClient client;

    private Che4zRuntime() {
        client = new SearchPathClient();
        injector = Guice.createInjector(Modules.override(new CliModule()).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(CobolLanguageClient.class).toInstance(client);
            }
        }));
        engine = injector.getInstance(CobolLanguageEngine.class);
        documentService = injector.getInstance(DocumentModelService.class);
    }

    static synchronized Che4zRuntime instance() {
        if (instance == null) {
            instance = new Che4zRuntime();
        }
        return instance;
    }

    synchronized Analysis analyze(String uri, String text, List<Path> copybookSearchPaths) {
        client.setSearchPaths(copybookSearchPaths);
        AnalysisConfig config = AnalysisConfig.defaultConfig(CopybookProcessingMode.ENABLED);
        documentService.openDocument(uri, text, "COBOL");
        AnalysisResult result;
        try {
            result = engine.run(uri, text, config, CobolLanguageId.COBOL);
        } finally {
            documentService.closeDocument(uri);
        }
        CstCapture capture = null;
        try {
            capture = captureCst(uri, text, config);
        } catch (RuntimeException e) {
            // CST が取れないと動詞の判定が原文の先頭語による近似に落ちるだけなので、解析は継続する
        }
        return new Analysis(result, capture);
    }

    private CstCapture captureCst(String uri, String text, AnalysisConfig config) {
        DialectService dialectService = injector.getInstance(DialectService.class);
        MessageService messageService = injector.getInstance(MessageService.class);
        GrammarPreprocessor grammarPreprocessor = injector.getInstance(GrammarPreprocessor.class);
        CleanerPreprocessor preprocessor = injector.getInstance(TrueDialectServiceImpl.class)
                .getPreprocessor(CobolLanguageId.COBOL);

        Pipeline<AnalysisContext> pipeline = new Pipeline<>();
        pipeline.add(new IbmCleanupStage(preprocessor));
        pipeline.add(new DialectCompilerDirectiveStage(dialectService));
        pipeline.add(new CompilerDirectivesStage(messageService));
        pipeline.add(new DialectProcessingStage(dialectService, preprocessor));
        pipeline.add(new PreprocessorStage(grammarPreprocessor, preprocessor));
        pipeline.add(new ImplicitDialectProcessingStage(dialectService));
        pipeline.add(new ParserStage(messageService, injector.getInstance(ParseTreeListener.class)));

        AnalysisContext context = new AnalysisContext(null, config,
                injector.getInstance(BenchmarkService.class).startSession(), uri, text,
                CobolLanguageId.COBOL);
        PipelineResult pipelineResult = pipeline.run(context);
        Object data = pipelineResult.getLastStageResult().getData();
        if (!(data instanceof ParserStageResult parserResult)) {
            return null;
        }
        return CstCapture.build(parserResult, context, uri);
    }
}
