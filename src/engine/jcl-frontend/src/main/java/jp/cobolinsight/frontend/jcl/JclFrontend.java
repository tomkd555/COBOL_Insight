package jp.cobolinsight.frontend.jcl;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import jp.cobolinsight.frontend.jcl.mapa.CatchableErrorListener;
import jp.cobolinsight.frontend.jcl.mapa.DdStatement;
import jp.cobolinsight.frontend.jcl.mapa.DdStatementAmalgamation;
import jp.cobolinsight.frontend.jcl.mapa.JCLLexer;
import jp.cobolinsight.frontend.jcl.mapa.JCLPPLexer;
import jp.cobolinsight.frontend.jcl.mapa.JCLPPParser;
import jp.cobolinsight.frontend.jcl.mapa.JCLParser;
import jp.cobolinsight.frontend.jcl.mapa.JclStep;
import jp.cobolinsight.frontend.jcl.mapa.Job;
import jp.cobolinsight.frontend.jcl.mapa.JobListener;
import jp.cobolinsight.frontend.jcl.mapa.PPJob;
import jp.cobolinsight.frontend.jcl.mapa.PPListener;
import jp.cobolinsight.frontend.jcl.mapa.PPProc;
import jp.cobolinsight.frontend.jcl.mapa.Proc;
import jp.cobolinsight.frontend.jcl.mapa.StdoutLexerErrorListener;
import jp.cobolinsight.frontend.jcl.mapa.TheCLI;

/**
 * jcl-frontend の入口。MAPA 由来のパーサで JCL を前処理(インストリーム PROC 分離・
 * INCLUDE 取込・シンボリック解決)し、ジョブ→ステップ→PGM/PROC→DD の結果モデルを返す。
 *
 * <p>処理の流れは MAPA の Demo01(CLI デモ)と同一だが、ファイル出力の代わりに
 * {@link JclParseResult} を組み立てる。
 */
public final class JclFrontend {

	private final Logger logger = Logger.getLogger(JclFrontend.class.getName());

	/**
	 * @param jclFile パース対象の JCL ファイル
	 * @param symbolValues 外部から与えるシンボリック値(例: SYSUID)。null 可
	 */
	public JclParseResult parse(Path jclFile, Map<String, String> symbolValues) throws IOException {
		return parse(jclFile, symbolValues, List.of());
	}

	/**
	 * JCL をパースする。前処理の各段は一時ディレクトリへ作業ファイルを書き出し、それらは JVM の
	 * 終了時に削除される。結果の行番号は前処理後の作業ファイル上の行番号である。
	 *
	 * @param jclFile パース対象の JCL ファイル
	 * @param symbolValues 外部から与えるシンボリック値(例: SYSUID)。null 可
	 * @param procedureLibraryPaths カタログ化 PROC・INCLUDE メンバの検索パス
	 */
	public JclParseResult parse(Path jclFile, Map<String, String> symbolValues,
			List<String> procedureLibraryPaths) throws IOException {
		this.logger.setLevel(Level.WARNING);
		TheCLI cli = new TheCLI(procedureLibraryPaths, symbolValues == null ? Map.of() : symbolValues, this.logger);
		File baseDir = newTempDir(cli);

		byte[] bytes = Files.readAllBytes(jclFile);
		if (bytes.length <= 2 || bytes[0] != '/' || bytes[1] != '/') {
			throw new JclParseException(jclFile + " first two bytes not \"//\", not JCL and/or not UTF8");
		}

		ArrayList<PPProc> procsPP = new ArrayList<>();
		ArrayList<PPJob> jobsPP = new ArrayList<>();
		File aFileRewritten = rewriteWithoutCol72to80(jclFile.toString(), baseDir, cli);
		lexAndParsePP(jobsPP, procsPP, aFileRewritten.getPath(), 1, baseDir, cli);
		if (jobsPP.isEmpty()) {
			throw new JclParseException(jclFile + " contains no jobs");
		}

		ArrayList<ParsedJob> parsedJobs = new ArrayList<>();
		for (PPJob j : jobsPP) {
			j.resolveParmedIncludes();
			File jobFile = j.rewriteJobAndSeparateInstreamProcs();
			PPJob rJob = j.iterativelyResolveIncludes(jobFile);
			File finalJobFile = rJob.rewriteWithParmsResolved();
			rJob.resolveProcs();

			ArrayList<Proc> procs = new ArrayList<>();
			ArrayList<Job> jobs = new ArrayList<>();
			lexAndParse(jobs, procs, finalJobFile.getPath(), 1, cli);
			if (jobs.isEmpty()) {
				throw new JclParseException(jclFile + " job " + j.getJobName() + " failed to parse");
			}
			Job job = jobs.get(0);
			job.setTmpDirs(baseDir, rJob.getJobDir(), rJob.getProcDir());
			job.setOrdNb(rJob.getOrdNb());
			job.lexAndParseProcs();
			job.processSYSTSIN();
			parsedJobs.add(toParsedJob(job));
		}

		return new JclParseResult(List.copyOf(parsedJobs));
	}

	private ParsedJob toParsedJob(Job job) {
		ArrayList<ParsedStep> steps = new ArrayList<>();
		for (JclStep step : job.getSteps()) {
			steps.add(toParsedStep(step));
		}
		return new ParsedJob(job.getJobName(), List.copyOf(steps));
	}

	private ParsedStep toParsedStep(JclStep step) {
		String programName = null;
		String procName = null;
		List<ParsedStep> procSteps = List.of();
		if (step.isExecPgm()) {
			programName = step.getPgmExecuted();
		} else if (step.isExecProc()) {
			procName = step.getProcExecuted();
			Proc proc = step.getProc();
			if (proc != null) {
				ArrayList<ParsedStep> inner = new ArrayList<>();
				for (JclStep procStep : proc.getSteps()) {
					inner.add(toParsedStep(procStep));
				}
				procSteps = List.copyOf(inner);
			}
		}
		ArrayList<ParsedDd> dds = new ArrayList<>();
		for (DdStatementAmalgamation dda : step.getDdStatements()) {
			for (DdStatement dd : dda.getDds()) {
				String dsn = null;
				if (dd.getDsnParms().get("DSNAME") != null) {
					dsn = dd.getDsnParms().get("DSNAME").getResolvedValue();
				}
				dds.add(new ParsedDd(dd.getDdName(), dsn, ddLine(dd, step)));
			}
		}
		return new ParsedStep(step.getStepName(), programName, procName, condText(step),
				step.getLine(), List.copyOf(dds), procSteps);
	}

	/** COND 句のテキスト表現(例: COND=(4,LT,STEP010))。COND 句が無ければ null。 */
	private String condText(JclStep step) {
		if (step.getExecPgmStmtCtx() != null) {
			for (JCLParser.ExecParameterContext p : step.getExecPgmStmtCtx().execParameter()) {
				if (p.execParmCOND() != null) {
					return p.execParmCOND().getText();
				}
			}
		} else if (step.getExecProcStmtCtx() != null) {
			for (JCLParser.ExecParameterOverridesContext p : step.getExecProcStmtCtx().execParameterOverrides()) {
				if (p.execParmCOND() != null) {
					return p.execParmCOND().getText();
				}
			}
		}
		return null;
	}

	private int ddLine(DdStatement dd, JclStep step) {
		if (dd.getDdStmtCtx() != null) {
			return dd.getDdStmtCtx().getStart().getLine();
		}
		if (dd.getDdStmtConcatCtx() != null) {
			return dd.getDdStmtConcatCtx().getStart().getLine();
		}
		return step.getLine();
	}

	/** MAPA Demo01.lexAndParsePP と同処理。 */
	private void lexAndParsePP(
			ArrayList<PPJob> jobs
			, ArrayList<PPProc> procs
			, String fileName
			, int fileNb
			, File baseDir
			, TheCLI cli
			) throws IOException {
		CharStream cs = CharStreams.fromFileName(fileName);
		JCLPPLexer.ckCol72 = false;
		JCLPPLexer lexer = new JCLPPLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new StdoutLexerErrorListener());
		CommonTokenStream tokens = new CommonTokenStream(lexer);

		JCLPPParser parser = new JCLPPParser(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(new CatchableErrorListener());

		ParseTree tree;
		try {
			tree = parser.startRule();
		} catch (ParseCancellationException e) {
			throw new JclParseException(fileName + " preprocessor parse error", e);
		}

		ParseTreeWalker walker = new ParseTreeWalker();
		PPListener listener = new PPListener(jobs, procs, fileName, fileNb, baseDir, null, null, this.logger, cli);
		walker.walk(listener, tree);
	}

	/** MAPA Demo01.lexAndParse と同処理。 */
	private void lexAndParse(
			ArrayList<Job> jobs
			, ArrayList<Proc> procs
			, String fileName
			, int fileNb
			, TheCLI cli
			) throws IOException {
		CharStream cs = CharStreams.fromFileName(fileName);
		JCLLexer lexer = new JCLLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new StdoutLexerErrorListener());
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JCLParser parser = new JCLParser(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(new CatchableErrorListener());

		ParseTree tree;
		try {
			tree = parser.startRule();
		} catch (ParseCancellationException e) {
			throw new JclParseException(fileName + " parse error", e);
		}

		ParseTreeWalker walker = new ParseTreeWalker();
		JobListener listener = new JobListener(jobs, procs, fileName, fileNb, this.logger, cli);
		walker.walk(listener, tree);
	}

	/** MAPA Demo01.lex と同処理。COMMENTS チャネルのトークンを収集する。 */
	private ArrayList<Token> lex(String fileName) throws IOException {
		CharStream cs = CharStreams.fromFileName(fileName);
		JCLPPLexer.ckCol72 = true;
		JCLPPLexer lexer = new JCLPPLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new StdoutLexerErrorListener());
		CommonTokenStream cmtokens = new CommonTokenStream(lexer, JCLPPLexer.COMMENTS);
		ArrayList<Token> tokens = new ArrayList<>();
		while (cmtokens.LA(1) != CommonTokenStream.EOF) {
			if (cmtokens.LT(1).getType() == JCLPPLexer.COL_72
			|| cmtokens.LT(1).getType() == JCLPPLexer.COMMENT_TEXT
			|| cmtokens.LT(1).getType() == JCLPPLexer.COMMENT_FLAG) {
				tokens.add(cmtokens.LT(1));
			}
			cmtokens.consume();
		}
		return tokens;
	}

	/**
	 * MAPA Demo01.rewriteWithoutCol72to80 と同処理。72〜80桁を除去した作業ファイルを作る。
	 *
	 * <p>JCL では72桁目の非空白が継続を表す。72桁目を空白へ落とすと、コメントの継続行が独立した
	 * 文として読まれるため、継続元がコメントだった場合は次行の3桁目へ {@code *} を置いてコメント行に
	 * 直す(addSplat)。72桁以降に現れたコメント本文は空白へ置き換える。
	 */
	private File rewriteWithoutCol72to80(String aFileName, File baseDir, TheCLI cli) throws IOException {
		ArrayList<Token> tokens = lex(aFileName);
		File aFile = new File(aFileName);
		LineNumberReader src = new LineNumberReader(new FileReader(aFile));
		File tmp = new File(
			baseDir.toString()
			+ File.separator
			+ aFile.getName()
			+ "-"
			+ UUID.randomUUID()
			);
		tmp.deleteOnExit();
		PrintWriter out = new PrintWriter(tmp);
		String inLine;
		boolean addSplat = false;
		while ((inLine = src.readLine()) != null) {
			StringBuilder newLine = new StringBuilder(inLine);
			ArrayList<Token> onThisLine = new ArrayList<>();
			Token col72 = null;
			Token cmBefore72 = null;
			Token cmAfter72 = null;
			Token cmFlag = null;
			for (Token t : tokens) {
				if (t.getLine() == src.getLineNumber()) {
					onThisLine.add(t);
					if (t.getType() == JCLPPLexer.COMMENT_FLAG) {
						cmFlag = t;
					}
					if (t.getType() == JCLPPLexer.COMMENT_TEXT) {
						if (t.getText().trim().length() > 0) {
							if (t.getCharPositionInLine() < 71) {
								cmBefore72 = t;
							} else {
								cmAfter72 = t;
							}
						}
					}
					if (cmFlag == null && t.getType() == JCLPPLexer.COL_72 && t.getText().trim().length() > 0) {
						col72 = t;
					}
				}
			}
			if (addSplat) {
				if (newLine.length() > 2 && newLine.charAt(2) == ' ') {
					newLine.setCharAt(2, '*');
				} else {
					addSplat = false;
				}
			}
			if (onThisLine.size() > 0) {
				if (cmBefore72 != null && col72 != null && !operandEndsWithComma(inLine, cmBefore72)) {
					addSplat = true;
				} else {
					addSplat = false;
				}
				if (cmAfter72 != null) {
					int start = cmAfter72.getCharPositionInLine();
					int end = start + cmAfter72.getText().length();
					String spaces = String.format("%1$" + ((end - start) + 1) + "s", " ");
					newLine.replace(start, end, spaces);
				}
				if (col72 != null) {
					newLine.setCharAt(71, ' ');
				}
			}
			out.println(newLine.toString());
		}
		src.close();
		out.close();
		cli.setPosixAttributes(tmp);
		return tmp;
	}

	/**
	 * 行内コメントの手前にあるオペランド部がカンマで終わるか。カンマで終わる場合、72桁目の非空白は
	 * オペランドの継続を表すのであって、コメントの継続を表さない。この区別を欠くと、続く継続行を
	 * コメント行へ書き換えてしまい、ジョブとステップが解析結果から消える。
	 */
	private boolean operandEndsWithComma(String line, Token commentBefore72) {
		int end = Math.min(commentBefore72.getCharPositionInLine(), line.length());
		return line.substring(0, end).stripTrailing().endsWith(",");
	}

	/** MAPA Demo01.newTempDir と同処理。 */
	private File newTempDir(TheCLI cli) throws IOException {
		File tmpDir = Files.createTempDirectory("JclFrontend-").toFile();
		cli.setPosixAttributes(tmpDir);
		tmpDir.deleteOnExit();
		return tmpDir;
	}
}
