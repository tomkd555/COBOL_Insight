package jp.cobolinsight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `callgraph` サブコマンド。資産フォルダを解析して呼出関係グラフを構築し、SQLiteの
 * NODE・CALL_EDGE表へ保存したうえで、単一グラフモデルをJSON/DOTで出力する。SVG/PNGは
 * graphviz-java(JVM内)で生成する。出力先の指定が無い場合はJSONを標準出力へ書く。
 */
@Command(name = "callgraph", mixinStandardHelpOptions = true,
        description = "呼出関係グラフを構築してSQLiteへ保存し、JSON/DOT/SVG/PNGで出力する")
public final class CallGraphCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

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
        ScanRunner.Result result = ScanRunner.runWithGraph(options.toRunnerOptions());
        try {
            if (jsonFile != null) {
                Files.writeString(jsonFile, result.callGraph().toJson(), StandardCharsets.UTF_8);
            }
            if (dotFile != null) {
                Files.writeString(dotFile, result.callGraph().toDot(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
