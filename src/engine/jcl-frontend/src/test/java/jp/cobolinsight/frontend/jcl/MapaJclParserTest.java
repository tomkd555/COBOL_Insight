package jp.cobolinsight.frontend.jcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;

class MapaJclParserTest {

	private static final Path REPO_ROOT = Paths.get("..", "..", "..").toAbsolutePath().normalize();

	private static DecodedSource decodedSource(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		String text = new String(bytes, StandardCharsets.UTF_8);
		int[] offsets = new int[text.length()];
		int byteOffset = 0;
		for (int i = 0; i < text.length(); i++) {
			offsets[i] = byteOffset;
			byteOffset += String.valueOf(text.charAt(i)).getBytes(StandardCharsets.UTF_8).length;
		}
		return new DecodedSource(file.toString(), text, bytes, offsets,
			new EncodingInfo("UTF-8", 1.0, false, false));
	}

	private static JclStep step(JclJobModel job, String name) {
		return job.steps().stream()
			.filter(s -> name.equals(s.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("step " + name + " not found in " + job.steps()));
	}

	private static String dsn(JclStep step, String ddName) {
		return step.ddStatements().stream()
			.filter(d -> ddName.equals(d.ddName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("DD " + ddName + " not found in " + step.ddStatements()))
			.datasetName()
			.orElseThrow(() -> new AssertionError("DD " + ddName + " has no DSN"));
	}

	@Test
	void parsesSykd010IntoJobModel() throws Exception {
		Path file = REPO_ROOT.resolve("samples").resolve("jcl").resolve("SYKD010.jcl");
		ParseOutcome<JclJobModel> outcome = new MapaJclParser().parse(decodedSource(file), List.of());

		assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
		JclJobModel job = outcome.value().orElseThrow();
		assertEquals("SYKD010", job.jobName());
		assertEquals(file.toString(), job.sourceFile());
		assertEquals(2, job.steps().size());

		JclStep step010 = step(job, "STEP010");
		assertEquals(JclExecKind.PGM, step010.execKind());
		assertEquals("SYK001", step010.target());
		assertEquals(Optional.empty(), step010.condition());
		assertEquals("SYKT.D250718.ORDER.DAILY", dsn(step010, "ORDIN"));
		assertEquals("SYKW.D250718.ORDER.VALID", dsn(step010, "ORDVALID"));

		JclStep step020 = step(job, "STEP020");
		assertEquals(JclExecKind.PGM, step020.execKind());
		assertEquals("SYK002", step020.target());
		assertEquals(Optional.of("COND=(4,LT,STEP010)"), step020.condition());
		assertEquals("SYKV.ORDER.MASTER", dsn(step020, "ORDMSTR"));
	}

	@Test
	void parsesSykd020WithExpandedProcSteps() throws Exception {
		Path file = REPO_ROOT.resolve("samples").resolve("jcl").resolve("SYKD020.jcl");
		ParseOutcome<JclJobModel> outcome = new MapaJclParser().parse(decodedSource(file), List.of());

		assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
		JclJobModel job = outcome.value().orElseThrow();
		assertEquals("SYKD020", job.jobName());
		// STEP010(PGM)、STEP020(PROC 呼出)、STEP020.STEP020(PROC 展開後の PGM)
		assertEquals(3, job.steps().size());

		JclStep step010 = step(job, "STEP010");
		assertEquals(JclExecKind.PGM, step010.execKind());
		assertEquals("SYK006", step010.target());
		assertEquals("SYKW.D250718.STOCK.EXTRACT", dsn(step010, "STKEXTR"));

		JclStep step020 = step(job, "STEP020");
		assertEquals(JclExecKind.PROC, step020.execKind());
		assertEquals("SYKPRC01", step020.target());

		JclStep procStep = step(job, "STEP020.STEP020");
		assertEquals(JclExecKind.PGM, procStep.execKind());
		assertEquals("SYK007", procStep.target());
		assertEquals("SYKW.D250718.STOCK.EXTRACT", dsn(procStep, "STKEXTR"));
	}

	@Test
	void reportsFailureAsErrorFinding() {
		byte[] bytes = "PLAIN TEXT, NOT JCL\n".getBytes(StandardCharsets.UTF_8);
		String text = new String(bytes, StandardCharsets.UTF_8);
		int[] offsets = new int[text.length()];
		for (int i = 0; i < text.length(); i++) {
			offsets[i] = i;
		}
		DecodedSource source = new DecodedSource("not-jcl.txt", text, bytes, offsets,
			new EncodingInfo("UTF-8", 1.0, false, false));

		ParseOutcome<JclJobModel> outcome = new MapaJclParser().parse(source, List.of());

		assertTrue(!outcome.isSuccess());
		Finding finding = outcome.failureFinding().orElseThrow();
		assertEquals(Finding.PARSE_FAILURE_RULE_ID, finding.ruleId());
	}
}
