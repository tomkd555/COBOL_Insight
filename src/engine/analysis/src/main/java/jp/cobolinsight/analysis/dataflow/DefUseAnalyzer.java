package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility that determines the variables a statement defines (assigns to) and the
 * variables it references, by regex analysis of the statement text
 * ({@link SimpleStatement#text()} / {@link CompoundStatement#conditionText()}). The semantic
 * model keeps only the statement text, not its constituent parts, so definition and reference
 * sides are split from the position of each verb's clauses (TO, GIVING, INTO, and so on).
 *
 * <p>Variable names are extracted as data-name tokens (runs of alphanumerics, hyphens, and
 * non-ASCII characters containing at least one letter character), excluding COBOL reserved
 * words, figurative constants, numeric literals, and string literals. Extracted names are
 * normalized by uppercasing. This judgment is approximate and aims for accuracy sufficient to
 * pass the samples and the synthesized test data.
 */
final class DefUseAnalyzer {

    private DefUseAnalyzer() {
    }

    /** Data-name token. Starts and ends with an alphanumeric character; hyphens are allowed inside. */
    private static final Pattern TOKEN =
            Pattern.compile("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?");

    private static final Set<String> RESERVED = Set.of(
            // verbs / clauses
            "MOVE", "TO", "FROM", "GIVING", "BY", "INTO", "COMPUTE", "ADD", "SUBTRACT",
            "MULTIPLY", "DIVIDE", "REMAINDER", "INITIALIZE", "REPLACING", "SET", "UP", "DOWN",
            "READ", "WRITE", "REWRITE", "DELETE", "START", "OPEN", "CLOSE", "ACCEPT", "DISPLAY",
            "STRING", "UNSTRING", "DELIMITED", "DELIMITER", "POINTER", "COUNT", "TALLYING",
            "OVERFLOW", "PERFORM", "VARYING", "UNTIL", "TIMES", "THRU", "THROUGH", "AFTER",
            "BEFORE", "TEST", "CALL", "USING", "RETURNING", "GOBACK", "STOP", "RUN", "EXIT",
            "PROGRAM", "CONTINUE", "NEXT", "SENTENCE", "GO", "DEPENDING", "IF", "THEN", "ELSE",
            "EVALUATE", "WHEN", "ALSO", "INSPECT", "SEARCH", "MERGE", "SORT", "RELEASE", "RETURN",
            // scope terminators
            "END-IF", "END-EVALUATE", "END-PERFORM", "END-COMPUTE", "END-READ", "END-WRITE",
            "END-REWRITE", "END-DELETE", "END-START", "END-STRING", "END-UNSTRING", "END-CALL",
            "END-ADD", "END-SUBTRACT", "END-MULTIPLY", "END-DIVIDE", "END-SEARCH", "END-EXEC",
            "END-RECEIVE",
            // relational / logical / qualifier
            "AND", "OR", "NOT", "IS", "EQUAL", "EQUALS", "GREATER", "LESS", "THAN", "EXCEEDS",
            "OF", "IN", "ROUNDED", "CORRESPONDING", "CORR", "ON", "OFF", "SIZE", "ERROR",
            "INVALID", "KEY", "AT", "END", "NUMERIC", "ALPHABETIC", "ALPHABETIC-LOWER",
            "ALPHABETIC-UPPER", "POSITIVE", "NEGATIVE", "ADVANCING", "LINE", "LINES", "PAGE",
            "UPON", "WITH", "MODE",
            // CICS / terminal
            "EXEC", "SQL", "CICS", "RECEIVE", "SEND", "MAP", "MAPSET", "LENGTH", "RESP", "RESP2",
            "NOHANDLE", "ERASE",
            // figurative constants
            "ZERO", "ZEROS", "ZEROES", "SPACE", "SPACES", "HIGH-VALUE", "HIGH-VALUES",
            "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES", "NULL", "NULLS", "TRUE", "FALSE", "ALL");

    /** Determines the variables a statement defines and references. */
    static DefUse extract(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return extractSimple(simple.verb(), simple.text());
        }
        if (statement instanceof CompoundStatement compound) {
            if (compound.kind() == ControlKind.LOOP) {
                return performVars(compound.conditionText(), false, false);
            }
            return new DefUse(Set.of(), names(clean(compound.conditionText())));
        }
        if (statement instanceof GoToStatement goTo) {
            return goTo.dependingOn()
                    .map(v -> new DefUse(Set.of(), names(clean(v))))
                    .orElse(DefUse.EMPTY);
        }
        return DefUse.EMPTY;
    }

    /**
     * The receiving target of ACCEPT / CICS RECEIVE. Used by taint tracking as an
     * EXTERNAL_INPUT taint source. Limited to one variable; a syntax with multiple receiving
     * targets treats only the first data name as the taint source.
     */
    static Set<String> externalInputTargets(Statement statement) {
        if (!(statement instanceof SimpleStatement simple)) {
            return Set.of();
        }
        String u = clean(simple.text());
        if ("ACCEPT".equals(simple.verb().toUpperCase(Locale.ROOT))) {
            int accept = kw(u, "ACCEPT");
            String region = accept >= 0 ? tail(u, accept, "ACCEPT") : u;
            String target = firstDataName(cutBefore(region, " FROM "));
            return target == null ? Set.of() : Set.of(target);
        }
        if (u.contains(" RECEIVE ") && kw(u, "INTO") >= 0) {
            int into = kw(u, "INTO");
            String target = firstDataName(tail(u, into, "INTO"));
            return target == null ? Set.of() : Set.of(target);
        }
        return Set.of();
    }

    private static DefUse extractSimple(String verbRaw, String text) {
        String verb = verbRaw.toUpperCase(Locale.ROOT);
        String u = clean(text);
        return switch (verb) {
            case "MOVE" -> move(u);
            case "SET" -> set(u);
            case "COMPUTE" -> compute(u);
            case "ADD" -> add(u);
            case "SUBTRACT" -> subtract(u);
            case "MULTIPLY" -> multiply(u);
            case "DIVIDE" -> divide(u);
            case "INITIALIZE" -> initialize(u);
            case "READ" -> read(u);
            case "ACCEPT" -> accept(u);
            case "DISPLAY" -> new DefUse(Set.of(), names(cutBefore(u, " UPON ")));
            case "STRING" -> stringVerb(u);
            case "UNSTRING" -> unstring(u);
            case "CALL" -> call(u);
            case "PERFORM" -> performVars(text, true, true);
            // WRITE/REWRITE/GOBACK/STOP/EXIT/CONTINUE etc.: treat all names as references
            // (conservative, no definitions)
            default -> new DefUse(Set.of(), names(u));
        };
    }

    private static DefUse move(String u) {
        int to = kw(u, "TO");
        if (to < 0) {
            return new DefUse(Set.of(), names(u));
        }
        Segment send = split(u.substring(0, to));
        Segment recv = split(tail(u, to, "TO"));
        Set<String> uses = union(send.outside(), send.inside(), recv.inside());
        return new DefUse(recv.outside(), uses);
    }

    private static DefUse set(String u) {
        int conn = kw(u, "TO");
        String connKw = "TO";
        int up = kw(u, "UP");
        int down = kw(u, "DOWN");
        if (up >= 0 && (conn < 0 || up < conn)) {
            conn = up;
            connKw = "UP";
        }
        if (down >= 0 && (conn < 0 || down < conn)) {
            conn = down;
            connKw = "DOWN";
        }
        if (conn < 0) {
            return new DefUse(Set.of(), names(u));
        }
        Segment target = split(u.substring(0, conn));
        Segment value = split(tail(u, conn, connKw));
        Set<String> uses = union(target.inside(), value.outside(), value.inside());
        return new DefUse(target.outside(), uses);
    }

    private static DefUse compute(String u) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-COMPUTE ");
        int eq = body.indexOf('=');
        if (eq < 0) {
            return new DefUse(Set.of(), names(body));
        }
        Segment left = split(body.substring(0, eq));
        Set<String> uses = union(left.inside(), names(body.substring(eq + 1)));
        return new DefUse(left.outside(), uses);
    }

    private static DefUse add(String u) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-ADD ");
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            Set<String> defs = split(tail(body, giving, "GIVING")).outside();
            Set<String> uses = names(body.substring(0, giving));
            return new DefUse(defs, uses);
        }
        int to = kw(body, "TO");
        if (to < 0) {
            return new DefUse(Set.of(), names(body));
        }
        Segment recv = split(tail(body, to, "TO"));
        Set<String> uses = union(names(body.substring(0, to)), recv.outside(), recv.inside());
        return new DefUse(recv.outside(), uses);
    }

    private static DefUse subtract(String u) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-SUBTRACT ");
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            Set<String> defs = split(tail(body, giving, "GIVING")).outside();
            Set<String> uses = names(body.substring(0, giving));
            return new DefUse(defs, uses);
        }
        int from = kw(body, "FROM");
        if (from < 0) {
            return new DefUse(Set.of(), names(body));
        }
        Segment recv = split(tail(body, from, "FROM"));
        Set<String> uses = union(names(body.substring(0, from)), recv.outside(), recv.inside());
        return new DefUse(recv.outside(), uses);
    }

    private static DefUse multiply(String u) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-MULTIPLY ");
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            Set<String> defs = split(tail(body, giving, "GIVING")).outside();
            Set<String> uses = names(body.substring(0, giving));
            return new DefUse(defs, uses);
        }
        int by = kw(body, "BY");
        if (by < 0) {
            return new DefUse(Set.of(), names(body));
        }
        Segment recv = split(tail(body, by, "BY"));
        Set<String> uses = union(names(body.substring(0, by)), recv.outside(), recv.inside());
        return new DefUse(recv.outside(), uses);
    }

    private static DefUse divide(String u) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-DIVIDE ");
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            int remainder = kw(body, "REMAINDER");
            String givingRegion = remainder >= 0
                    ? body.substring(0, remainder).substring(giving)
                    : tail(body, giving, "GIVING");
            Set<String> defs = new LinkedHashSet<>(split(givingRegion).outside());
            if (remainder >= 0) {
                defs.addAll(split(tail(body, remainder, "REMAINDER")).outside());
            }
            Set<String> uses = names(body.substring(0, giving));
            return new DefUse(defs, uses);
        }
        int into = kw(body, "INTO");
        if (into >= 0) {
            Segment recv = split(tail(body, into, "INTO"));
            Set<String> uses = union(names(body.substring(0, into)), recv.outside(), recv.inside());
            return new DefUse(recv.outside(), uses);
        }
        return new DefUse(Set.of(), names(body));
    }

    private static DefUse initialize(String u) {
        int replacing = kw(u, "REPLACING");
        String items = replacing >= 0 ? u.substring(0, replacing) : u;
        Set<String> defs = split(items).outside();
        Set<String> uses = replacing >= 0 ? names(tail(u, replacing, "REPLACING")) : Set.of();
        return new DefUse(defs, uses);
    }

    private static DefUse read(String u) {
        Set<String> defs = new LinkedHashSet<>();
        Set<String> uses = new LinkedHashSet<>();
        int into = kw(u, "INTO");
        if (into >= 0) {
            String rec = firstDataName(tail(u, into, "INTO"));
            if (rec != null) {
                defs.add(rec);
            }
        }
        int key = kw(u, "KEY");
        if (key >= 0) {
            String k = firstDataName(cutBefore(tail(u, key, "KEY"), " AT ", " END", " INVALID "));
            if (k != null) {
                uses.add(k);
            }
        }
        return new DefUse(defs, uses);
    }

    private static DefUse accept(String u) {
        int accept = kw(u, "ACCEPT");
        String region = accept >= 0 ? cutBefore(tail(u, accept, "ACCEPT"), " FROM ") : u;
        String target = firstDataName(region);
        return target == null ? DefUse.EMPTY : new DefUse(Set.of(target), Set.of());
    }

    private static DefUse stringVerb(String u) {
        int into = kw(u, "INTO");
        if (into < 0) {
            return new DefUse(Set.of(), names(u));
        }
        Set<String> uses = new LinkedHashSet<>(names(u.substring(0, into)));
        String dest = cutBefore(tail(u, into, "INTO"), " WITH ", " POINTER ", " ON ", " END-STRING ");
        Set<String> defs = new LinkedHashSet<>(split(dest).outside());
        int pointer = kw(u, "POINTER");
        if (pointer >= 0) {
            String pv = firstDataName(tail(u, pointer, "POINTER"));
            if (pv != null) {
                defs.add(pv);
                uses.add(pv);
            }
        }
        return new DefUse(defs, uses);
    }

    private static DefUse unstring(String u) {
        int into = kw(u, "INTO");
        if (into < 0) {
            return new DefUse(Set.of(), names(u));
        }
        Set<String> uses = new LinkedHashSet<>(names(u.substring(0, into)));
        String dest = cutBefore(tail(u, into, "INTO"), " ON ", " END-UNSTRING ");
        Set<String> defs = new LinkedHashSet<>(split(dest).outside());
        int pointer = kw(u, "POINTER");
        if (pointer >= 0) {
            String pv = firstDataName(tail(u, pointer, "POINTER"));
            if (pv != null) {
                defs.add(pv);
                uses.add(pv);
            }
        }
        return new DefUse(defs, uses);
    }

    private static DefUse call(String u) {
        int using = kw(u, "USING");
        int returning = kw(u, "RETURNING");
        int progEnd = using >= 0 ? using : (returning >= 0 ? returning : u.length());
        int call = kw(u, "CALL");
        String progRegion = call >= 0 ? u.substring(call, Math.min(progEnd, u.length())) : "";
        Set<String> uses = new LinkedHashSet<>(names(progRegion));
        if (using >= 0) {
            int usingEnd = returning >= 0 && returning > using ? returning : u.length();
            uses.addAll(names(u.substring(using, usingEnd)));
        }
        Set<String> defs = new LinkedHashSet<>();
        if (returning >= 0) {
            String rv = firstDataName(tail(u, returning, "RETURNING"));
            if (rv != null) {
                defs.add(rv);
            }
        }
        return new DefUse(defs, uses);
    }

    /**
     * From a PERFORM's control clauses (the text of a paragraph PERFORM, or the conditionText
     * of an inline PERFORM), determines the loop control variables (the VARYING/AFTER targets =
     * definitions) and the referenced variables of FROM/BY/UNTIL/TIMES.
     *
     * @param hasParagraph      whether the head contains a paragraph name (true for a paragraph PERFORM, false for inline)
     * @param emptyIfNoControl  whether to return empty when there is no control clause (true for a plain paragraph-PERFORM call)
     */
    private static DefUse performVars(String text, boolean hasParagraph, boolean emptyIfNoControl) {
        String u = clean(text);
        int varying = kw(u, "VARYING");
        int until = kw(u, "UNTIL");
        int times = kw(u, "TIMES");
        boolean hasControl = varying >= 0 || until >= 0 || times >= 0;
        if (!hasControl && emptyIfNoControl) {
            return DefUse.EMPTY;
        }
        Set<String> defs = new LinkedHashSet<>();
        if (varying >= 0) {
            addFirstName(defs, tail(u, varying, "VARYING"));
        }
        int from = 0;
        while (true) {
            int a = u.indexOf(" AFTER ", from);
            if (a < 0) {
                break;
            }
            addFirstName(defs, u.substring(a + 7));
            from = a + 7;
        }
        Set<String> uses = new LinkedHashSet<>(names(u));
        if (hasParagraph) {
            int perform = kw(u, "PERFORM");
            if (perform >= 0) {
                removeFirstName(uses, tail(u, perform, "PERFORM"));
            }
            int thru = kw(u, "THRU");
            String thruKw = "THRU";
            if (thru < 0) {
                thru = kw(u, "THROUGH");
                thruKw = "THROUGH";
            }
            if (thru >= 0) {
                removeFirstName(uses, tail(u, thru, thruKw));
            }
        }
        uses.removeAll(defs);
        return new DefUse(defs, uses);
    }

    // ---- Token extraction and text manipulation ----

    /** Variable names split into outside- and inside-parentheses. On the receiving side, outside = definition, inside (subscript / reference modification) = reference. */
    private record Segment(Set<String> outside, Set<String> inside) {
    }

    private static Segment split(String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder in = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0) {
                out.append(c);
            } else {
                in.append(c);
            }
        }
        return new Segment(names(out.toString()), names(in.toString()));
    }

    private static Set<String> names(String segment) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(segment);
        while (m.find()) {
            String tok = m.group();
            if (isDataName(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String firstDataName(String segment) {
        Matcher m = TOKEN.matcher(segment);
        while (m.find()) {
            String tok = m.group();
            if (isDataName(tok)) {
                return tok;
            }
        }
        return null;
    }

    private static void addFirstName(Set<String> target, String segment) {
        String name = firstDataName(segment);
        if (name != null) {
            target.add(name);
        }
    }

    private static void removeFirstName(Set<String> target, String segment) {
        String name = firstDataName(segment);
        if (name != null) {
            target.remove(name);
        }
    }

    private static boolean isDataName(String tok) {
        boolean hasLetter = false;
        for (int i = 0; i < tok.length(); ) {
            int cp = tok.codePointAt(i);
            if (Character.isLetter(cp)) {
                hasLetter = true;
                break;
            }
            i += Character.charCount(cp);
        }
        return hasLetter && !RESERVED.contains(tok);
    }

    /** Blanks out string literals, uppercases, collapses whitespace to single spaces, and pads front and back with a space. */
    private static String clean(String text) {
        String stripped = stripLiterals(text).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return " " + stripped + " ";
    }

    /** Replaces literals with the same number of blank characters. Since the length does not change, this does not affect column-position calculations. */
    private static String stripLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Position of a space-delimited keyword's start (the position of the leading space). -1 if absent. */
    private static int kw(String u, String keyword) {
        return u.indexOf(" " + keyword + " ");
    }

    /** keywordStart is the position of the leading space. Returns everything from right after the keyword (after the next space) to the end. */
    private static String tail(String u, int keywordStart, String keyword) {
        return u.substring(keywordStart + keyword.length() + 2);
    }

    private static String cutBefore(String s, String... markers) {
        int min = s.length();
        for (String marker : markers) {
            int idx = s.indexOf(marker);
            if (idx >= 0 && idx < min) {
                min = idx;
            }
        }
        return s.substring(0, min);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> s : sets) {
            out.addAll(s);
        }
        return out;
    }
}
