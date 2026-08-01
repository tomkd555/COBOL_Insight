package jp.cobolinsight.jclfrontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JclFrontendTest {

	private static final Path REPO_ROOT = Paths.get("..", "..", "..").toAbsolutePath().normalize();
	private static final Map<String, String> SYMBOLS = Map.of("SYSUID", "USER01");

	private JclParseResult parseSample(String fileName) throws Exception {
		return new JclFrontend().parse(REPO_ROOT.resolve("samples").resolve("jcl").resolve(fileName), SYMBOLS);
	}

	private static ParsedStep step(ParsedJob job, String stepName) {
		return job.steps().stream()
			.filter(s -> stepName.equals(s.stepName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("step " + stepName + " not found in " + job.steps()));
	}

	private static String dsn(ParsedStep step, String ddName) {
		return step.ddStatements().stream()
			.filter(d -> ddName.equals(d.ddName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("DD " + ddName + " not found in " + step.ddStatements()))
			.datasetName();
	}

	@Test
	void parsesSykd010WithResolvedSymbolics() throws Exception {
		JclParseResult result = parseSample("SYKD010.jcl");

		assertEquals(1, result.jobs().size());
		ParsedJob job = result.jobs().get(0);
		assertEquals("SYKD010", job.jobName());
		assertEquals(2, job.steps().size());

		ParsedStep step010 = step(job, "STEP010");
		assertEquals("SYK001", step010.programName());
		assertEquals("SYKT.D250718.ORDER.DAILY", dsn(step010, "ORDIN"));
		assertEquals("SYKW.D250718.ORDER.VALID", dsn(step010, "ORDVALID"));
		assertEquals("SYKW.D250718.ORDER.ERROR", dsn(step010, "ORDERR"));

		ParsedStep step020 = step(job, "STEP020");
		assertEquals("SYK002", step020.programName());
		assertEquals("SYKW.D250718.ORDER.VALID", dsn(step020, "ORDVALID"));
		assertEquals("SYKV.ORDER.MASTER", dsn(step020, "ORDMSTR"));
	}

	@Test
	void parsesSykd020WithInstreamProc() throws Exception {
		JclParseResult result = parseSample("SYKD020.jcl");

		assertEquals(1, result.jobs().size());
		ParsedJob job = result.jobs().get(0);
		assertEquals("SYKD020", job.jobName());
		assertEquals(2, job.steps().size());

		ParsedStep step010 = step(job, "STEP010");
		assertEquals("SYK006", step010.programName());
		assertEquals("SYKT.D250718.STOCK.DAILY", dsn(step010, "STKIN"));
		assertEquals("SYKW.D250718.STOCK.EXTRACT", dsn(step010, "STKEXTR"));

		ParsedStep step020 = step(job, "STEP020");
		assertNull(step020.programName());
		assertEquals("SYKPRC01", step020.procName());
		assertEquals(1, step020.procSteps().size());
		ParsedStep procStep = step020.procSteps().get(0);
		assertEquals("STEP020", procStep.stepName());
		assertEquals("SYK007", procStep.programName());
		// PROC 実引数 CYCLE=&CYCLE 経由でも DSN の &CYCLE が解決されること
		assertEquals("SYKW.D250718.STOCK.EXTRACT", dsn(procStep, "STKEXTR"));
	}

	@Test
	void parsesSykd030WithResolvedSymbolics() throws Exception {
		JclParseResult result = parseSample("SYKD030.jcl");

		assertEquals(1, result.jobs().size());
		ParsedJob job = result.jobs().get(0);
		assertEquals("SYKD030", job.jobName());
		assertEquals(2, job.steps().size());

		ParsedStep step010 = step(job, "STEP010");
		assertEquals("SYK001", step010.programName());
		assertEquals("SYKW.D250718.ORDER.ERROR", dsn(step010, "ORDIN"));
		assertEquals("SYKW.D250718.ORDER.RERUN.VALID", dsn(step010, "ORDVALID"));
		assertEquals("SYKW.D250718.ORDER.RERUN.ERROR", dsn(step010, "ORDERR"));

		ParsedStep step020 = step(job, "STEP020");
		assertEquals("SYK002", step020.programName());
		assertEquals("SYKW.D250718.ORDER.RERUN.VALID", dsn(step020, "ORDVALID"));
		assertEquals("SYKV.ORDER.MASTER", dsn(step020, "ORDMSTR"));
	}

	/** 継続する JOB カードを含む MAPA 同梱のテストデータを、全ジョブそろってパースできること。 */
	@Test
	void parsesMapaTest0003WithContinuedJobCards() throws Exception {
		JclParseResult result = new JclFrontend().parse(
			REPO_ROOT.resolve("src").resolve("vendor").resolve("mapa").resolve("jcl").resolve("testdata")
					.resolve("test0003.jcl"),
			SYMBOLS);

		assertEquals(11, result.jobs().size());
		ParsedJob job = result.jobs().stream()
			.filter(j -> "ABC00010".equals(j.jobName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("job ABC00010 not found"));
		assertEquals(1, job.steps().size());
		assertEquals("IEFBR14", job.steps().get(0).programName());
	}

	@Test
	void jobCardContinuationKeepsFollowingStatementsInRange() throws Exception {
		// JOB カードが2行に継続しても、後続の SET/EXEC/DD が同一ジョブの範囲に入ること
		JclParseResult result = parseSample("SYKD010.jcl");
		ParsedJob job = result.jobs().get(0);
		assertNotNull(job);
		assertTrue(job.steps().stream().anyMatch(s -> "SYK001".equals(s.programName())));
	}
}
