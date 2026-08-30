package jp.cobolinsight.frontend.cobol;

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
 * Bootstraps and reuses the Che4z engine's Guice setup. Builds the injector and engine only once,
 * then runs run() and the CST capture pipeline per file.
 *
 * <p>The engine keeps open-document state internally, so analysis is run exclusively. The copybook
 * search path is also shared state on the language client, and is swapped in for each analysis.
 */
final class Che4zRuntime {

    /** The engine's AST analysis result plus the CST captured from the same source. cstCapture is null if capture failed. */
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
            // If CST capture fails, verb detection just falls back to an approximation based on
            // the source's leading word, so analysis continues
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
