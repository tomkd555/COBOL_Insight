package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `call-graph`。資産フォルダを解析して呼出関係グラフを構築し、SQLiteの NODE・CALL_EDGE表へ
 * 保存したうえで、単一グラフモデルをJSON/DOTで出力する。SVG/PNGは graphviz-java(JVM内)で
 * 生成する。出力先の指定が無い場合はJSONを標準出力へ書く。
 */
@Command(name = "call-graph", mixinStandardHelpOptions = true,
        description = "呼出関係グラフを構築してSQLiteへ保存し、JSON/DOT/SVG/PNGで出力する")
public final class CallGraphCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--json", paramLabel = "FILE", description = "グラフのJSON出力先")
    Path jsonFile;

    @Option(names = "--dot", paramLabel = "FILE", description = "グラフのDOT出力先")
    Path dotFile;

    @Option(names = "--svg", paramLabel = "FILE", description = "グラフのSVG出力先(graphviz-java)")
    Path svgFile;

    @Option(names = "--png", paramLabel = "FILE", description = "グラフのPNG出力先(graphviz-java)")
    Path pngFile;

    @Override
    public Integer call() {
        ScanOutcome result = Pipelines.scan(options.inputDir, options.databaseFile,
                options.resolvedCopybookPaths(), options.codepageOverrides,
                ruleOptions.reportingRuleSet());
        if (jsonFile != null) {
            Paths.writeString(jsonFile, result.callGraph().toJson());
        }
        if (dotFile != null) {
            Paths.writeString(dotFile, result.callGraph().toDot());
        }
        if (svgFile != null) {
            GraphRenderer.writeSvg(result.callGraph().toDot(), svgFile);
        }
        if (pngFile != null) {
            GraphRenderer.writePng(result.callGraph().toDot(), pngFile);
        }
        if (jsonFile == null && dotFile == null && svgFile == null && pngFile == null) {
            System.out.println(result.callGraph().toJson());
        }
        return result.summary().exitCode();
    }
}
