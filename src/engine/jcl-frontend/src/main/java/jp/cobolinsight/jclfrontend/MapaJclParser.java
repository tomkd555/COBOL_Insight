package jp.cobolinsight.jclfrontend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.jcl.JclDdStatement;
import jp.cobolinsight.engineapi.jcl.JclExecKind;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.JclParser;
import jp.cobolinsight.engineapi.spi.ParseOutcome;

/**
 * {@link JclParser} の MAPA 実装。{@link JclFrontend} のパース結果を engine-api の
 * ジョブ構造モデルへ変換する。
 *
 * <p>EXEC PROC のステップは、PROC 呼出ステップ(execKind=PROC)に続けて、展開後の
 * 内部ステップを修飾名「ステップ名.PROC内ステップ名」で平坦化して並べる。
 *
 * <p>位置情報の行番号は PROC 展開・シンボリック解決後の作業ファイル上の行番号であり、
 * PROC を含まないジョブでは原ファイルの行番号と一致する。byteOffset は不明として扱う。
 * 1 ファイルに複数ジョブがある場合は先頭のジョブを返す。
 */
public final class MapaJclParser implements JclParser {

	@Override
	public ParseOutcome<JclJobModel> parse(DecodedSource source, List<Path> procedureLibraryPaths) {
		Path tmpDir = null;
		Path tmpFile = null;
		try {
			tmpDir = Files.createTempDirectory("jclfrontend-spi-");
			tmpDir.toFile().deleteOnExit();
			Path fileName = Paths.get(source.path()).getFileName();
			tmpFile = tmpDir.resolve(fileName == null ? "input.jcl" : fileName.toString());
			Files.writeString(tmpFile, source.text(), StandardCharsets.UTF_8);
			tmpFile.toFile().deleteOnExit();

			List<String> includePaths = procedureLibraryPaths.stream().map(Path::toString).toList();
			JclParseResult result = new JclFrontend().parse(tmpFile, Map.of(), includePaths);
			return ParseOutcome.success(toModel(result.jobs().get(0), source.path()));
		} catch (IOException | RuntimeException e) {
			return ParseOutcome.failure(Finding.parseFailure(
					SourcePosition.fileStart(source.path()),
					source.path() + " の JCL パースに失敗した: " + e));
		} finally {
			deleteQuietly(tmpFile);
			deleteQuietly(tmpDir);
		}
	}

	/** 一時ファイルを明示削除する。削除できない場合は deleteOnExit に委ねる。 */
	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			// deleteOnExit が残るため無視する
		}
	}

	private JclJobModel toModel(ParsedJob job, String sourceFile) {
		ArrayList<JclStep> steps = new ArrayList<>();
		for (ParsedStep step : job.steps()) {
			addSteps(steps, step, "", sourceFile);
		}
		return new JclJobModel(job.jobName(), sourceFile, Optional.empty(), steps);
	}

	private void addSteps(List<JclStep> out, ParsedStep step, String namePrefix, String sourceFile) {
		String name = namePrefix.isEmpty() ? step.stepName() : namePrefix + "." + step.stepName();
		JclExecKind kind = step.programName() != null ? JclExecKind.PGM : JclExecKind.PROC;
		String target = step.programName() != null ? step.programName() : step.procName();
		ArrayList<JclDdStatement> dds = new ArrayList<>();
		for (ParsedDd dd : step.ddStatements()) {
			dds.add(new JclDdStatement(dd.ddName(), Optional.ofNullable(dd.datasetName()),
					position(sourceFile, dd.line())));
		}
		out.add(new JclStep(name, kind, target, Optional.ofNullable(step.condition()), dds,
				position(sourceFile, step.line())));
		for (ParsedStep procStep : step.procSteps()) {
			addSteps(out, procStep, name, sourceFile);
		}
	}

	private SourcePosition position(String sourceFile, int line) {
		return new SourcePosition(sourceFile, Math.max(1, line), 1,
				SourcePosition.UNKNOWN_BYTE_OFFSET);
	}
}
