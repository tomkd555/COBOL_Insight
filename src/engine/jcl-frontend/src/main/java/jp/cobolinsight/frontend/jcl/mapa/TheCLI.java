package jp.cobolinsight.frontend.jcl.mapa;

/*
取込時変更: 上流の TheCLI(commons-cli によるコマンドライン解析)を、同名・同フィールドの
プログラム呼出用クラスへ置き換えたもの。移入クラス群が参照するフィールドとユーティリティ
メソッドは上流と同じ意味を保つ。
上流著作権表示: Copyright (C) 2019, 2020 Craig Schneiderwent.  All rights reserved.
*/

import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.logging.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/**
Global repository of option values passed around the imported MAPA classes.
*/
public class TheCLI {
	public final Logger LOGGER;
	public ArrayList<String> fileNamesToProcess = new ArrayList<>();
	public ArrayList<String> staticProcPaths = new ArrayList<>();
	public Hashtable<String, String> mappedProcPaths = new Hashtable<>();
	public Hashtable<String, String> mappedCntlPaths = new Hashtable<>();
	public String outcsvFileName = null;
	public String outtreeFileName = null;
	public Boolean unitTest = false;
	public Boolean saveTemp = false;
	public Integer sanity = 20;
	public File setFile = null;
	public ArrayList<SetSymbolValue> setSym = new ArrayList<>();
	public ArrayList<PPSetSymbolValue> PPsetSym = new ArrayList<>();
	private String myName = this.getClass().getName();

	public TheCLI(
			List<String> includePaths
			, Map<String, String> symbolValues
			, Logger logger
			) throws IOException {
		this.LOGGER = logger;
		this.staticProcPaths.addAll(includePaths);
		if (symbolValues != null && !symbolValues.isEmpty()) {
			this.setFile = this.writeSet(symbolValues);
			this.PPsetSym = lookForPPSetSymbols(this.setFile.getCanonicalPath());
			this.setSym = lookForSetSymbols(this.setFile.getCanonicalPath());
		}
	}

	public int getSanity() {
		return sanity.intValue();
	}

	private File writeSet(Map<String, String> symbolValues) throws IOException {
		File tmpDir = this.newTempDir();
		File tmp = new File(tmpDir.toString() + File.separator + "set-" + UUID.randomUUID());
		tmp.deleteOnExit();
		PrintWriter out = new PrintWriter(tmp);
		out.printf("//UNUSED JOB%n");
		for (Map.Entry<String, String> e: symbolValues.entrySet()) {
			out.printf("// SET %s=%s%n", e.getKey(), e.getValue());
		}
		out.close();

		return tmp;
	}

	public File newTempDir() throws IOException {
		File tmpDir = Files.createTempDirectory("JclFrontend-").toFile();

		tmpDir.deleteOnExit();

		return tmpDir;
	}

	public ArrayList<PPSetSymbolValue> lookForPPSetSymbols(String fileName) throws IOException {
		this.LOGGER.fine("lookForPPSetSymbols");
		ArrayList<PPSetSymbolValue> sets = new ArrayList<>();
		CharStream cs = CharStreams.fromFileName(fileName);
		JCLPPLexer lexer = new JCLPPLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new StdoutLexerErrorListener());
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JCLPPParser parser = new JCLPPParser(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(new StdoutParserErrorListener());

		ParseTree tree = parser.startRule();

		ParseTreeWalker walker = new ParseTreeWalker();

		PPSetSymbolValueListener listener = new PPSetSymbolValueListener(sets, fileName, this.LOGGER, this);

		this.LOGGER.finer("----------walking tree with " + listener.getClass().getName());

		walker.walk(listener, tree);

		return sets;
	}

	public ArrayList<SetSymbolValue> lookForSetSymbols(String fileName) throws IOException {
		this.LOGGER.fine("lookForSetSymbols");
		ArrayList<SetSymbolValue> sets = new ArrayList<>();
		CharStream cs = CharStreams.fromFileName(fileName);
		JCLLexer lexer = new JCLLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new StdoutLexerErrorListener());
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JCLParser parser = new JCLParser(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(new StdoutParserErrorListener());

		ParseTree tree = parser.startRule();

		ParseTreeWalker walker = new ParseTreeWalker();

		SetSymbolValueListener listener = new SetSymbolValueListener(sets, fileName, this.LOGGER, this);

		this.LOGGER.finer("----------walking tree with " + listener.getClass().getName());

		walker.walk(listener, tree);

		return sets;
	}

	/**
	Used to create temporary directories.  Global.
	*/
	public File newTempDir(File baseDir, String prfx, Boolean saveTemp) {
		File tmpDir = null;
		try {
			tmpDir = Files.createTempDirectory(baseDir.toPath(), prfx).toFile();
		} catch (Exception e) {
			this.LOGGER.severe(this.myName + " Exception " + e + " encountered in newTempDir");
			throw new IllegalStateException(e);
		}

		this.setPosixAttributes(tmpDir);

		if (saveTemp) {
		} else {
			tmpDir.deleteOnExit();
		}

		return tmpDir;
	}

	/**
	Used to set file attributes if necessary.  Global.
	*/
	public void setPosixAttributes(File aFile) {
		String attr = null;

		if (aFile.isDirectory()) {
			attr = "rwxr-x---";
		} else {
			attr = "rw-r-----";
		}

		if (aFile.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Set<PosixFilePermission> perms = PosixFilePermissions.fromString(attr);
			try {
				Files.setPosixFilePermissions(aFile.toPath(), perms);
			} catch (Exception e) {
				this.LOGGER.severe(this.myName + " Exception " + e + " encountered in setPosixAttributes");
				throw new IllegalStateException(e);
			}
		}
	}
}
