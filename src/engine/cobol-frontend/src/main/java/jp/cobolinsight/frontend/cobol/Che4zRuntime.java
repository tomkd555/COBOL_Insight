package jp.cobolinsight.frontend.cobol;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.eclipse.lsp.cobol.cli.di.CliModule;
import org.eclipse.lsp.cobol.common.AnalysisConfig;
import org.eclipse.lsp.cobol.common.AnalysisResult;
import org.eclipse.lsp.cobol.common.CleanerPreprocessor;
import org.eclipse.lsp.cobol.common.SubroutineService;
import org.eclipse.lsp.cobol.common.copybook.CopybookProcessingMode;
import org.eclipse.lsp.cobol.common.copybook.CopybookService;
import org.eclipse.lsp.cobol.common.dialects.CobolLanguageId;
import org.eclipse.lsp.cobol.common.dialects.DialectOutcome;
import org.eclipse.lsp.cobol.common.dialects.TrueDialectService;
import org.eclipse.lsp.cobol.common.message.MessageService;
import org.eclipse.lsp.cobol.common.pipeline.Pipeline;
import org.eclipse.lsp.cobol.common.pipeline.Stage;
import org.eclipse.lsp.cobol.common.pipeline.StageResult;
import org.eclipse.lsp.cobol.core.engine.CobolLanguageEngine;
import org.eclipse.lsp.cobol.core.engine.analysis.AnalysisContext;
import org.eclipse.lsp.cobol.core.engine.dialects.DialectService;
import org.eclipse.lsp.cobol.core.engine.processor.AstProcessor;
import org.eclipse.lsp.cobol.core.engine.symbols.SymbolsRepository;
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
import org.eclipse.lsp.cobol.dialects.ibm.TransformTreeStage;
import org.eclipse.lsp.cobol.lsp.jrpc.CobolLanguageClient;
import org.eclipse.lsp.cobol.service.DocumentModelService;
import org.eclipse.lsp.cobol.service.settings.CachingConfigurationService;
import org.eclipse.lsp.cobol.service.settings.layout.CodeLayoutStore;

import java.nio.file.Path;
import java.util.List;

/**
 * Bootstraps and reuses the Che4z engine's Guice setup. Builds the injector and engine only once,
 * then runs one analysis per file, which also captures the CST.
 *
 * <p>The engine keeps open-document state internally, so analysis is run exclusively. The copybook
 * search path is also shared state on the language client, and is swapped in for each analysis.
 */
final class Che4zRuntime {

    /** The engine's AST analysis result plus the CST captured from the same source. cstCapture is null if capture failed. */
    record Analysis(AnalysisResult result, CstCapture cstCapture) {
    }

    private static Che4zRuntime instance;

    private final CobolLanguageEngine engine;
    private final DocumentModelService documentService;
    private final CopybookService copybooks;
    private final CapturingDialects dialects;
    private final SearchPathClient client;
    /** The search paths the last analysis ran under; null before the first one. */
    private List<Path> lastSearchPaths;
    /** The copybook cache scope of the last analysis; null before the first one. */
    private CopybookCacheScope lastScope;

    /**
     * How long Che4z keeps the copybooks it resolved: every analysis that passes one scope shares
     * them, and the first analysis under another scope reads them afresh. A parser holds one scope
     * for the pass over a set of programs it makes.
     */
    static final class CopybookCacheScope {
    }

    private Che4zRuntime() {
        client = new SearchPathClient();
        Injector injector = Guice.createInjector(Modules.override(new CliModule())
                .with(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(CobolLanguageClient.class).toInstance(client);
                        bind(TrueDialectService.class).to(CapturingDialects.class);
                    }
                }));
        engine = injector.getInstance(CobolLanguageEngine.class);
        documentService = injector.getInstance(DocumentModelService.class);
        copybooks = injector.getInstance(CopybookService.class);
        dialects = injector.getInstance(CapturingDialects.class);
    }

    static synchronized Che4zRuntime instance() {
        if (instance == null) {
            instance = new Che4zRuntime();
        }
        return instance;
    }

    /**
     * Analyses one program under {@code scope}: every analysis of one scan passes the same scope,
     * and the copybooks resolved under it are read once.
     */
    synchronized Analysis analyze(String uri, String text, List<Path> copybookSearchPaths,
            CopybookCacheScope scope) {
        // Che4z caches a resolved copybook, and the failure to resolve one, under the copybook's
        // name and the program's URI, never under the search path it looked in or the content it
        // read, and holds it for hours. Without this, one analysis of a program whose search path
        // cannot reach its copybooks answers every later analysis of that same program in this
        // process, and a copybook edited between two scans in one process keeps its old content.
        // So the cache lives for one scope under one search path: a whole folder analysed in one
        // scan keeps it and reads each copybook once, and the next scan reads them afresh. The
        // implicit copybooks the engine ships are kept either way.
        if (!copybookSearchPaths.equals(lastSearchPaths) || scope != lastScope) {
            copybooks.invalidateCache(true);
            lastSearchPaths = List.copyOf(copybookSearchPaths);
            lastScope = scope;
        }
        client.setSearchPaths(copybookSearchPaths);
        AnalysisConfig config = AnalysisConfig.defaultConfig(CopybookProcessingMode.ENABLED);
        documentService.openDocument(uri, text, "COBOL");
        dialects.capture = null;
        AnalysisResult result;
        try {
            result = engine.run(uri, text, config, CobolLanguageId.COBOL);
        } finally {
            documentService.closeDocument(uri);
        }
        return new Analysis(result, dialects.capture);
    }

    /**
     * The dialects Che4z analyses with, the COBOL pipeline built stage for stage as Che4z's own
     * IBM dialect builds it, with the CST captured the moment its parser stage finishes. The
     * context then holds the expanded document and the copybooks as they stand after parsing,
     * and the one run that builds the AST also gives the CST. The HP COBOL pipeline and the
     * preprocessors are Che4z's own.
     */
    @Singleton
    static final class CapturingDialects implements TrueDialectService<AnalysisContext> {

        private final TrueDialectServiceImpl dialects;
        private final Pipeline<AnalysisContext> cobol = new Pipeline<>();
        /** What the parser stage of the running analysis gave; null when capture failed. */
        private CstCapture capture;

        @Inject
        CapturingDialects(TrueDialectServiceImpl dialects, GrammarPreprocessor grammarPreprocessor,
                MessageService messageService, ParseTreeListener treeListener,
                SubroutineService subroutineService,
                CachingConfigurationService cachingConfigurationService,
                DialectService dialectService, AstProcessor astProcessor,
                SymbolsRepository symbolsRepository, CodeLayoutStore codeLayoutStore) {
            this.dialects = dialects;
            CleanerPreprocessor preprocessor = dialects.getPreprocessor(CobolLanguageId.COBOL);
            ParserStage parser = new ParserStage(messageService, treeListener);
            cobol.add(new IbmCleanupStage(preprocessor));
            cobol.add(new DialectCompilerDirectiveStage(dialectService));
            cobol.add(new CompilerDirectivesStage(messageService));
            cobol.add(new DialectProcessingStage(dialectService, preprocessor));
            cobol.add(new PreprocessorStage(grammarPreprocessor, preprocessor));
            cobol.add(new ImplicitDialectProcessingStage(dialectService));
            cobol.add(new Stage<AnalysisContext, ParserStageResult, DialectOutcome>() {
                @Override
                public StageResult<ParserStageResult> run(AnalysisContext context,
                        StageResult<DialectOutcome> previous) {
                    StageResult<ParserStageResult> result = parser.run(context, previous);
                    try {
                        capture = result.getData() == null ? null
                                : CstCapture.build(result.getData(), context,
                                        context.getDocumentUri());
                    } catch (RuntimeException e) {
                        // If CST capture fails, verb detection just falls back to an
                        // approximation based on the source's leading word, so analysis continues
                        capture = null;
                    }
                    return result;
                }

                @Override
                public String getName() {
                    return parser.getName();
                }
            });
            cobol.add(new TransformTreeStage(symbolsRepository, messageService, subroutineService,
                    cachingConfigurationService, dialectService, astProcessor, codeLayoutStore));
        }

        @Override
        public Pipeline<AnalysisContext> getPipeline(CobolLanguageId languageId) {
            return languageId == CobolLanguageId.COBOL ? cobol : dialects.getPipeline(languageId);
        }

        @Override
        public CleanerPreprocessor getPreprocessor(CobolLanguageId languageId) {
            return dialects.getPreprocessor(languageId);
        }
    }
}
