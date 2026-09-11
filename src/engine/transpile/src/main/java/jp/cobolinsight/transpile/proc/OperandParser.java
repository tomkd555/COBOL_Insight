package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.transpile.emit.Identifiers;
import jp.cobolinsight.transpile.emit.Literals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A lightweight tokenizer that re-parses {@code SimpleStatement.text} per verb to obtain operands.
 * Since the semantic model holds only the verb and the full text, this extracts the receiving,
 * sending, and condition parts of MOVE/COMPUTE/ADD/DISPLAY/CALL/PERFORM and similar statements by
 * lexical analysis, resolves them via {@link ProgramSymbols}, and maps them into the {@link ProcStmt}
 * intermediate representation. Syntax that cannot be interpreted falls back to a verbatim-comment
 * {@link ProcStmt.Untranslated} and is surfaced with a note.
 */
public final class OperandParser {

    private static final String NOTE_FILE_IO = "ファイル I/O は実行時ファイルモデルを持たないため注記のみ";
    private static final String NOTE_STRING = "文字列操作(STRING/INSPECT 等)は逐語コメントのみ";
    private static final String NOTE_CALL = "BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない";

    private final ProgramSymbols symbols;
    private final Map<SourceRange, EmbeddedBlock> embeddedByRange;
    private final SourceSlicer slicer;

    public OperandParser(ProgramSymbols symbols, Map<SourceRange, EmbeddedBlock> embeddedByRange,
            SourceSlicer slicer) {
        this.symbols = symbols;
        this.embeddedByRange = embeddedByRange;
        this.slicer = slicer;
    }

    // ---- Entry point for simple statements ----

    public List<ProcStmt> parseSimple(SimpleStatement s) {
        String verb = s.verb().toUpperCase(Locale.ROOT);
        SourceRange range = s.range();
        return switch (verb) {
            case "MOVE" -> parseMove(s.text(), range);
            case "ADD" -> parseAdd(s.text(), range);
            case "COMPUTE" -> parseCompute(s.text(), range);
            case "DISPLAY" -> parseDisplay(s.text(), range);
            case "CALL" -> parseCall(s.text(), range);
            case "PERFORM" -> parsePerform(s.text(), range);
            case "STOP" -> one(new ProcStmt.Return("STOP RUN", range, ""));
            case "GOBACK" -> one(new ProcStmt.Return("GOBACK", range, ""));
            case "EXIT" -> one(s.text().toUpperCase(Locale.ROOT).contains("PROGRAM")
                    ? new ProcStmt.Return("EXIT PROGRAM", range, "")
                    : new ProcStmt.NoOp("EXIT", range, ""));
            case "CONTINUE" -> one(new ProcStmt.NoOp("CONTINUE", range, ""));
            case "OPEN", "CLOSE", "READ", "WRITE", "REWRITE", "DELETE", "START" ->
                    untranslated(s.text(), range, NOTE_FILE_IO);
            case "STRING", "UNSTRING", "INSPECT" -> untranslated(s.text(), range, NOTE_STRING);
            case "EXEC SQL", "EXEC CICS" -> embeddedStub(s);
            case "ACCEPT" -> untranslated(s.text(), range, "ACCEPT(入力)は未対応");
            case "INITIALIZE" -> untranslated(s.text(), range, "INITIALIZE は未対応");
            case "SET" -> untranslated(s.text(), range, "SET は未対応");
            default -> untranslated(s.text(), range, "未対応の文(" + verb + ")");
        };
    }

    // ---- EXEC SQL / EXEC CICS (untranslatable block -> note stub) ----

    /**
     * Maps EXEC SQL / EXEC CICS to a note stub. Joins the {@link EmbeddedBlock} keyed by range to
     * obtain the command name and operands. The original text is recovered via {@link SourceSlicer};
     * when it is unavailable, the semantic model's normalized text is used instead.
     */
    private List<ProcStmt> embeddedStub(SimpleStatement s) {
        EmbeddedBlock block = embeddedByRange == null ? null : embeddedByRange.get(s.range());
        List<String> cobolLines = slicer != null ? slicer.rawLinesOf(s.range())
                : List.of(s.text().trim());
        String command;
        String note;
        if (block != null && block.kind().isCics()) {
            command = "EXEC CICS " + cicsCommand(block);
            note = command + " は直訳不能" + operandSuffix(block.operands());
        } else if (block != null) {
            command = "EXEC SQL " + sqlCommand(block.text());
            note = command + " は直訳不能";
        } else {
            command = s.verb().toUpperCase(Locale.ROOT).startsWith("EXEC CICS")
                    ? "EXEC CICS" : "EXEC SQL";
            note = command + " は直訳不能";
        }
        return one(new ProcStmt.EmbeddedStub(command, cobolLines, s.range(), note));
    }

    private static String cicsCommand(EmbeddedBlock block) {
        return switch (block.kind()) {
            case CICS_SEND_MAP -> "SEND MAP";
            case CICS_RECEIVE_MAP -> "RECEIVE MAP";
            case CICS_XCTL -> "XCTL";
            case CICS_LINK -> "LINK";
            case CICS_START -> "START";
            case CICS_RETURN_TRANSID, CICS_RETURN -> "RETURN";
            case CICS_HANDLE_CONDITION -> "HANDLE CONDITION";
            case CICS_OTHER -> otherCicsCommand(block.text());
            case SQL -> "";
        };
    }

    /** The first word after EXEC CICS, in uppercase: READ, WRITEQ, ASSIGN, ... */
    private static String otherCicsCommand(String text) {
        List<String> toks = splitTokens(text.trim());
        int i = 0;
        if (i < toks.size() && toks.get(i).equalsIgnoreCase("EXEC")) {
            i++;
        }
        if (i < toks.size() && toks.get(i).equalsIgnoreCase("CICS")) {
            i++;
        }
        return i < toks.size() ? toks.get(i).toUpperCase(Locale.ROOT) : "";
    }

    /** Returns the leading verb of an EXEC SQL body in uppercase (DECLARE ... CURSOR becomes "DECLARE CURSOR"). */
    private static String sqlCommand(String text) {
        List<String> toks = splitTokens(text.trim());
        int i = 0;
        if (i < toks.size() && toks.get(i).equalsIgnoreCase("EXEC")) {
            i++;
        }
        if (i < toks.size() && toks.get(i).equalsIgnoreCase("SQL")) {
            i++;
        }
        if (i >= toks.size()) {
            return "";
        }
        String verb = toks.get(i).toUpperCase(Locale.ROOT);
        if (verb.equals("DECLARE")) {
            for (String t : toks) {
                if (t.equalsIgnoreCase("CURSOR")) {
                    return "DECLARE CURSOR";
                }
            }
        }
        return verb;
    }

    private static String operandSuffix(Map<String, String> operands) {
        if (operands.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : operands.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return " (" + String.join(", ", parts) + ")";
    }

    // ---- MOVE ----

    private List<ProcStmt> parseMove(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        int toIdx = indexOfKeyword(toks, "TO");
        // Only interprets the form where the sending item is a single word immediately followed by
        // TO. Everything after TO is treated as a list of receiving items, and one assignment
        // statement is generated per receiving item.
        if (toIdx != 1 || toks.size() <= toIdx + 1) {
            return untranslated(text, range, "MOVE の構文を解釈できない(集団/CORR/参照修正)");
        }
        Optional<PExpr> src = parseOperand(toks.get(0));
        if (src.isEmpty()) {
            return untranslated(text, range, "MOVE 送信項目が集団または未解決");
        }
        List<ProcStmt> result = new ArrayList<>();
        for (int i = toIdx + 1; i < toks.size(); i++) {
            Optional<PExpr> target = parseOperand(toks.get(i));
            if (target.isEmpty() || !(target.get() instanceof PExpr.Ref ref)) {
                return untranslated(text, range, "MOVE 受信項目が集団または未解決");
            }
            result.add(new ProcStmt.Assign(ref, src.get(), range, moveNote(toks.get(0))));
        }
        return result;
    }

    private static String moveNote(String srcToken) {
        String upper = srcToken.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SPACE", "SPACES" -> "SPACES を半角空白1文字へ簡約";
            default -> "";
        };
    }

    // ---- ADD ----

    private List<ProcStmt> parseAdd(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        int givingIdx = indexOfKeyword(toks, "GIVING");
        int toIdx = indexOfKeyword(toks, "TO");
        if (toIdx >= 0 && givingIdx < 0) {
            List<PExpr> sources = parseOperands(toks.subList(0, toIdx));
            List<String> targetToks = toks.subList(toIdx + 1, toks.size());
            if (sources == null || targetToks.isEmpty()) {
                return untranslated(text, range, "ADD の被演算子が未解決");
            }
            List<ProcStmt> result = new ArrayList<>();
            for (String tt : targetToks) {
                Optional<PExpr> target = parseOperand(tt);
                if (target.isEmpty() || !(target.get() instanceof PExpr.Ref ref)) {
                    return untranslated(text, range, "ADD 受信項目が未解決");
                }
                List<PExpr> parts = new ArrayList<>();
                parts.add(ref);
                for (PExpr source : sources) {
                    parts.add(new PExpr.Op("+"));
                    parts.add(source);
                }
                result.add(new ProcStmt.Assign(ref, new PExpr.Arith(parts), range, ""));
            }
            return result;
        }
        if (givingIdx >= 0) {
            List<PExpr> sources = parseOperands(toks.subList(0, givingIdx));
            List<String> targetToks = toks.subList(givingIdx + 1, toks.size());
            if (sources == null || sources.isEmpty() || targetToks.isEmpty()) {
                return untranslated(text, range, "ADD GIVING の被演算子が未解決");
            }
            PExpr sum = joinWithOp(sources, "+");
            List<ProcStmt> result = new ArrayList<>();
            for (String tt : targetToks) {
                Optional<PExpr> target = parseOperand(tt);
                if (target.isEmpty() || !(target.get() instanceof PExpr.Ref ref)) {
                    return untranslated(text, range, "ADD GIVING 受信項目が未解決");
                }
                result.add(new ProcStmt.Assign(ref, sum, range, ""));
            }
            return result;
        }
        return untranslated(text, range, "ADD の構文を解釈できない");
    }

    // ---- COMPUTE ----

    private List<ProcStmt> parseCompute(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        int eqIdx = indexOfKeyword(toks, "=");
        if (eqIdx < 1 || eqIdx + 1 >= toks.size()) {
            return untranslated(text, range, "COMPUTE の構文を解釈できない");
        }
        Optional<PExpr> arith = parseArithmetic(String.join(" ", toks.subList(eqIdx + 1, toks.size())));
        if (arith.isEmpty()) {
            return untranslated(text, range, "COMPUTE の式に未解決の項目がある");
        }
        // ROUNDED instructs rounding to the receiving item's digit count. Since the translated
        // assignment does not perform rounding, a note records that the specification was dropped.
        // ROUNDED is written after the receiving item, so the note is decided first.
        String note = toks.subList(0, eqIdx).stream().anyMatch(t -> t.equalsIgnoreCase("ROUNDED"))
                ? "ROUNDED の丸めは対訳へ反映しない" : "";
        List<ProcStmt> result = new ArrayList<>();
        for (int i = 0; i < eqIdx; i++) {
            if (toks.get(i).equalsIgnoreCase("ROUNDED")) {
                continue;
            }
            Optional<PExpr> target = parseOperand(toks.get(i));
            if (target.isEmpty() || !(target.get() instanceof PExpr.Ref ref)) {
                return untranslated(text, range, "COMPUTE 受信項目が未解決");
            }
            result.add(new ProcStmt.Assign(ref, arith.get(), range, note));
        }
        return result.isEmpty() ? untranslated(text, range, "COMPUTE 受信項目が無い") : result;
    }

    // ---- DISPLAY ----

    private List<ProcStmt> parseDisplay(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        List<PExpr> operands = new ArrayList<>();
        String note = "";
        for (String tok : toks) {
            if (tok.equalsIgnoreCase("UPON") || tok.equalsIgnoreCase("WITH")) {
                note = "DISPLAY の UPON/WITH 句は省略";
                break;
            }
            Optional<PExpr> operand = parseOperand(tok);
            if (operand.isPresent()) {
                operands.add(operand.get());
            } else {
                operands.add(new PExpr.Lit(tok, true));
                note = "一部オペランドを未解決のまま文字列化";
            }
        }
        return one(new ProcStmt.Display(operands, range, note));
    }

    // ---- CALL ----

    private List<ProcStmt> parseCall(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        if (toks.isEmpty()) {
            return untranslated(text, range, "CALL の対象を解釈できない");
        }
        String targetToken = toks.get(0);
        String target = Literals.isQuoted(targetToken) ? Literals.unquote(targetToken) : targetToken;
        List<String> argNames = new ArrayList<>();
        int usingIdx = indexOfKeyword(toks, "USING");
        if (usingIdx >= 0) {
            for (int i = usingIdx + 1; i < toks.size(); i++) {
                String tok = toks.get(i);
                if (tok.equalsIgnoreCase("BY") || tok.equalsIgnoreCase("REFERENCE")
                        || tok.equalsIgnoreCase("CONTENT") || tok.equalsIgnoreCase("VALUE")) {
                    continue;
                }
                argNames.add(tok);
            }
        }
        return one(new ProcStmt.CallProgram(target, argNames, range, NOTE_CALL));
    }

    // ---- PERFORM(out-of-line)----

    private List<ProcStmt> parsePerform(String text, SourceRange range) {
        List<String> toks = splitTokens(stripFirstWord(text));
        if (toks.isEmpty()) {
            return untranslated(text, range, "PERFORM の対象を解釈できない");
        }
        String method = Identifiers.sanitize(toks.get(0));
        int varyingIdx = indexOfKeyword(toks, "VARYING");
        int untilIdx = indexOfKeyword(toks, "UNTIL");
        int timesIdx = indexOfKeyword(toks, "TIMES");
        int thruIdx = indexOfAnyKeyword(toks, "THRU", "THROUGH");

        if (varyingIdx >= 0 && untilIdx > varyingIdx) {
            int fromIdx = indexOfKeyword(toks, "FROM");
            int byIdx = indexOfKeyword(toks, "BY");
            if (fromIdx > varyingIdx && byIdx > fromIdx && untilIdx > byIdx) {
                Optional<PExpr> var = parseOperand(toks.get(varyingIdx + 1));
                Optional<PExpr> from = parseOperand(toks.get(fromIdx + 1));
                Optional<PExpr> by = parseOperand(toks.get(byIdx + 1));
                PCond until = parseCondition(String.join(" ", toks.subList(untilIdx + 1, toks.size())));
                if (var.isPresent() && var.get() instanceof PExpr.Ref varRef && from.isPresent()
                        && by.isPresent()) {
                    List<ProcStmt> body = List.of(new ProcStmt.PerformCall(method, range, ""));
                    return one(new ProcStmt.Loop(until, body, varRef, from.get(), by.get(), range,
                            PCond.untranslatableNote(until)));
                }
            }
            return untranslated(text, range, "PERFORM VARYING の被演算子が未解決");
        }
        if (untilIdx >= 0) {
            PCond until = parseCondition(String.join(" ", toks.subList(untilIdx + 1, toks.size())));
            List<ProcStmt> body = List.of(new ProcStmt.PerformCall(method, range, ""));
            return one(new ProcStmt.Loop(until, body, null, null, null, range,
                    PCond.untranslatableNote(until)));
        }
        if (timesIdx >= 1) {
            Optional<PExpr> count = parseOperand(toks.get(timesIdx - 1));
            if (count.isPresent()) {
                return one(new ProcStmt.PerformTimes(method, count.get(), range, ""));
            }
            return untranslated(text, range, "PERFORM TIMES の回数が未解決");
        }
        if (thruIdx >= 0 && thruIdx + 1 < toks.size()) {
            String method2 = Identifiers.sanitize(toks.get(thruIdx + 1));
            return one(new ProcStmt.PerformThru(List.of(method, method2), range,
                    "THRU 範囲は端点を順次呼出(中間段落は省略)"));
        }
        return one(new ProcStmt.PerformCall(method, range, ""));
    }

    // ---- Operand resolution ----

    Optional<PExpr> parseOperand(String rawToken) {
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        if (Literals.isQuoted(token)) {
            return Optional.of(new PExpr.Lit(Literals.unquote(token), true));
        }
        String upper = token.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "ZERO", "ZEROS", "ZEROES" -> {
                return Optional.of(new PExpr.Lit("0", false));
            }
            case "SPACE", "SPACES" -> {
                return Optional.of(new PExpr.Lit(" ", true));
            }
            // Among figurative constants, ones whose byte value depends on the character encoding
            // scheme (EBCDIC/ASCII) would diverge from the original meaning if mapped to a value.
            // Return them as unresolved, so the caller falls back to an untranslated form with a note.
            case "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES",
                    "NULL", "NULLS" -> {
                return Optional.empty();
            }
            default -> {
                // fall through
            }
        }
        if (token.matches("[+-]?\\d+(\\.\\d+)?")) {
            return Optional.of(new PExpr.Lit(token, false));
        }
        int paren = token.indexOf('(');
        if (paren > 0 && token.endsWith(")")) {
            String name = token.substring(0, paren);
            String inside = token.substring(paren + 1, token.length() - 1);
            Optional<ProgramSymbols.DataSymbol> field = symbols.field(name);
            if (field.isEmpty()) {
                return Optional.empty();
            }
            List<PExpr> subscripts = new ArrayList<>();
            for (String part : inside.trim().split("[,\\s]+")) {
                Optional<PExpr> sub = parseOperand(part);
                if (sub.isEmpty()) {
                    return Optional.empty();
                }
                subscripts.add(sub.get());
            }
            return Optional.of(new PExpr.Ref(field.get().fieldName(), field.get().isString(),
                    subscripts));
        }
        return symbols.field(token)
                .map(f -> new PExpr.Ref(f.fieldName(), f.isString(), List.of()));
    }

    private List<PExpr> parseOperands(List<String> tokens) {
        List<PExpr> result = new ArrayList<>();
        for (String token : tokens) {
            Optional<PExpr> operand = parseOperand(token);
            if (operand.isEmpty()) {
                return null;
            }
            result.add(operand.get());
        }
        return result;
    }

    private static PExpr joinWithOp(List<PExpr> operands, String op) {
        List<PExpr> parts = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) {
                parts.add(new PExpr.Op(op));
            }
            parts.add(operands.get(i));
        }
        return parts.size() == 1 ? parts.get(0) : new PExpr.Arith(parts);
    }

    // ---- Arithmetic expressions ----

    Optional<PExpr> parseArithmetic(String text) {
        List<PExpr> parts = new ArrayList<>();
        for (String raw : text.trim().split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String rest = raw;
            List<PExpr> trailing = new ArrayList<>();
            while (rest.startsWith("(")) {
                parts.add(new PExpr.Op("("));
                rest = rest.substring(1);
            }
            // A trailing ')' is peeled off as an expression closing paren only for the portion that
            // is unmatched within the token, so as not to mistakenly strip a subscript paren
            // (the ')' in A(1)).
            while (rest.endsWith(")") && count(rest, ')') > count(rest, '(')) {
                trailing.add(new PExpr.Op(")"));
                rest = rest.substring(0, rest.length() - 1);
            }
            if (!rest.isEmpty()) {
                if (rest.equals("+") || rest.equals("-") || rest.equals("*") || rest.equals("/")) {
                    parts.add(new PExpr.Op(rest));
                } else {
                    Optional<PExpr> operand = parseOperand(rest);
                    if (operand.isEmpty()) {
                        return Optional.empty();
                    }
                    parts.add(operand.get());
                }
            }
            parts.addAll(trailing);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(new PExpr.Arith(parts));
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    // ---- Condition expressions ----

    /**
     * Recovers a branch (IF) condition from the original text and maps it to a condition expression.
     * The semantic model's conditionText is composed from the ranges of the condition expression's
     * syntax-tree nodes, and drops class conditions and sign conditions (e.g. NOT NUMERIC) that have
     * no node. So instead, the original text of the condition is obtained by slicing from the IF
     * header in the original source up to just before the first statement on the THEN side, and
     * stripping the IF and THEN reserved words. When the original source is not supplied, or there is
     * no statement on the THEN side, the semantic model's text is used instead.
     */
    public PCond parseBranchCondition(CompoundStatement cs) {
        return parseCondition(branchConditionText(cs));
    }

    private String branchConditionText(CompoundStatement cs) {
        SourcePosition bodyStart = firstThenStatementStart(cs);
        if (slicer == null || bodyStart == null) {
            return cs.conditionText();
        }
        String text = stripBranchKeywords(slicer.between(cs.range().start(), bodyStart));
        return text.isEmpty() ? cs.conditionText() : text;
    }

    private static SourcePosition firstThenStatementStart(CompoundStatement cs) {
        for (StatementBlock block : cs.blocks()) {
            if (block.label().equalsIgnoreCase("THEN") && !block.statements().isEmpty()) {
                return block.statements().get(0).range().start();
            }
        }
        return null;
    }

    /** From the sliced IF header, removes the leading IF and trailing THEN at word boundaries. */
    private static String stripBranchKeywords(String text) {
        String result = text.trim();
        if (startsWithWord(result, "IF")) {
            result = result.substring(2).trim();
        }
        if (endsWithWord(result, "THEN")) {
            result = result.substring(0, result.length() - 4).trim();
        }
        return result;
    }

    private static boolean startsWithWord(String text, String word) {
        return text.regionMatches(true, 0, word, 0, word.length())
                && (text.length() == word.length() || !isWordChar(text.charAt(word.length())));
    }

    private static boolean endsWithWord(String text, String word) {
        int start = text.length() - word.length();
        return start >= 0 && text.regionMatches(true, start, word, 0, word.length())
                && (start == 0 || !isWordChar(text.charAt(start - 1)));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    public PCond parseCondition(String text) {
        List<String> toks = tokenizeCondition(text);
        if (toks.isEmpty()) {
            return new PCond.Raw(text);
        }
        CondParser parser = new CondParser(toks);
        PCond cond = parser.parseOr();
        if (cond == null || !parser.done()) {
            return new PCond.Raw(text.trim());
        }
        return cond;
    }

    private final class CondParser {
        private final List<String> toks;
        private int i;

        CondParser(List<String> toks) {
            this.toks = toks;
        }

        boolean done() {
            return i >= toks.size();
        }

        private String peek() {
            return i < toks.size() ? toks.get(i) : null;
        }

        private boolean matchKeyword(String kw) {
            if (peek() != null && peek().equalsIgnoreCase(kw)) {
                i++;
                return true;
            }
            return false;
        }

        PCond parseOr() {
            PCond left = parseAnd();
            while (left != null && matchKeyword("OR")) {
                PCond right = parseAnd();
                if (right == null) {
                    return null;
                }
                left = new PCond.Or(left, right);
            }
            return left;
        }

        PCond parseAnd() {
            PCond left = parsePrimary();
            while (left != null && matchKeyword("AND")) {
                PCond right = parsePrimary();
                if (right == null) {
                    return null;
                }
                left = new PCond.And(left, right);
            }
            return left;
        }

        PCond parsePrimary() {
            if (peek() == null) {
                return null;
            }
            String leftTok = toks.get(i++);
            String next = peek();
            boolean notFlag = false;
            if (next != null && next.equalsIgnoreCase("NOT")) {
                notFlag = true;
                i++;
                next = peek();
            }
            if (next != null && mapRelOp(next) != null) {
                RelOp op = mapRelOp(next);
                i++;
                if (peek() == null) {
                    return null;
                }
                String rightTok = toks.get(i++);
                Optional<PExpr> left = parseConditionOperand(leftTok);
                Optional<PExpr> right = parseConditionOperand(rightTok);
                if (left.isEmpty() || right.isEmpty()) {
                    return null;
                }
                RelOp effective = notFlag ? op.negate() : op;
                boolean stringCompare = ExprWriter.isString(left.get())
                        || ExprWriter.isString(right.get());
                return new PCond.Rel(left.get(), effective, right.get(), stringCompare);
            }
            if (notFlag) {
                return null;
            }
            return expandCondition(leftTok);
        }
    }

    private Optional<PExpr> parseConditionOperand(String token) {
        return parseOperand(token);
    }

    private PCond expandCondition(String token) {
        Optional<ProgramSymbols.ConditionSymbol> cond = symbols.condition(token);
        if (cond.isEmpty()) {
            return null;
        }
        ProgramSymbols.ConditionSymbol symbol = cond.get();
        PExpr parent = new PExpr.Ref(symbol.parent().fieldName(), symbol.parent().isString(),
                List.of());
        boolean parentIsString = symbol.parent().isString();
        PCond combined = null;
        for (String value : symbol.values()) {
            PCond one = valueCondition(parent, parentIsString, value);
            combined = combined == null ? one : new PCond.Or(combined, one);
        }
        return combined;
    }

    private PCond valueCondition(PExpr parent, boolean parentIsString, String value) {
        int thru = Literals.indexOfThru(value);
        if (thru >= 0) {
            PExpr lo = valueLiteral(value.substring(0, thru), parentIsString);
            PExpr hi = valueLiteral(value.substring(thru + Literals.thruLength()), parentIsString);
            return new PCond.And(new PCond.Rel(parent, RelOp.GE, lo, parentIsString),
                    new PCond.Rel(parent, RelOp.LE, hi, parentIsString));
        }
        return new PCond.Rel(parent, RelOp.EQ, valueLiteral(value, parentIsString), parentIsString);
    }

    private PExpr valueLiteral(String value, boolean parentIsString) {
        String v = value.trim();
        if (Literals.isQuoted(v)) {
            return new PExpr.Lit(Literals.unquote(v), parentIsString);
        }
        String upper = v.toUpperCase(Locale.ROOT);
        if (upper.equals("ZERO") || upper.equals("ZEROS") || upper.equals("ZEROES")) {
            return new PExpr.Lit("0", false);
        }
        if (upper.equals("SPACE") || upper.equals("SPACES")) {
            return new PExpr.Lit(" ", true);
        }
        return new PExpr.Lit(v, parentIsString);
    }

    /** Builds a condition from EVALUATE's subject and WHEN value (when subject=TRUE, the WHEN clause itself is interpreted as the condition). */
    public PCond evaluateArm(String subject, String whenLabel) {
        if (subject.trim().equalsIgnoreCase("TRUE")) {
            return parseCondition(whenLabel);
        }
        Optional<PExpr> left = parseOperand(subject.trim());
        Optional<PExpr> right = parseOperand(whenLabel.trim());
        if (left.isEmpty() || right.isEmpty()) {
            return new PCond.Raw(subject.trim() + " = " + whenLabel.trim());
        }
        boolean stringCompare = ExprWriter.isString(left.get()) || ExprWriter.isString(right.get());
        return new PCond.Rel(left.get(), RelOp.EQ, right.get(), stringCompare);
    }

    private static RelOp mapRelOp(String tok) {
        return switch (tok) {
            case "=" -> RelOp.EQ;
            case ">" -> RelOp.GT;
            case "<" -> RelOp.LT;
            case ">=" -> RelOp.GE;
            case "<=" -> RelOp.LE;
            case "<>" -> RelOp.NE;
            default -> null;
        };
    }

    // ---- Lexical analysis utilities ----

    static String stripFirstWord(String text) {
        String trimmed = text.trim();
        int space = 0;
        while (space < trimmed.length() && !Character.isWhitespace(trimmed.charAt(space))) {
            space++;
        }
        return trimmed.substring(Math.min(space, trimmed.length())).trim();
    }

    /** Splits on whitespace. Whitespace inside quotes and subscript parentheses is not split. */
    static List<String> splitTokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                current.append(c);
                continue;
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** Lexes a condition expression. Relational operators (= &lt; &gt; and their combinations) become independent tokens; operands and word forms become single tokens. */
    static List<String> tokenizeCondition(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '=' || c == '<' || c == '>') {
                int start = i;
                while (i < n && (text.charAt(i) == '=' || text.charAt(i) == '<'
                        || text.charAt(i) == '>')) {
                    i++;
                }
                tokens.add(text.substring(start, i));
                continue;
            }
            if (c == '\'' || c == '"') {
                int start = i;
                i++;
                while (i < n && text.charAt(i) != c) {
                    i++;
                }
                if (i < n) {
                    i++;
                }
                tokens.add(text.substring(start, i));
                continue;
            }
            int start = i;
            int depth = 0;
            while (i < n) {
                char d = text.charAt(i);
                if (d == '(') {
                    depth++;
                } else if (d == ')') {
                    if (depth > 0) {
                        depth--;
                    }
                } else if (depth == 0 && (Character.isWhitespace(d) || d == '=' || d == '<'
                        || d == '>')) {
                    break;
                }
                i++;
            }
            tokens.add(text.substring(start, i));
        }
        return tokens;
    }

    private static int indexOfKeyword(List<String> toks, String keyword) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).equalsIgnoreCase(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfAnyKeyword(List<String> toks, String... keywords) {
        for (int i = 0; i < toks.size(); i++) {
            for (String keyword : keywords) {
                if (toks.get(i).equalsIgnoreCase(keyword)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<ProcStmt> one(ProcStmt stmt) {
        return List.of(stmt);
    }

    private static List<ProcStmt> untranslated(String text, SourceRange range, String note) {
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            lines.add(line.trim());
        }
        return List.of(new ProcStmt.Untranslated(lines, range, note));
    }
}
