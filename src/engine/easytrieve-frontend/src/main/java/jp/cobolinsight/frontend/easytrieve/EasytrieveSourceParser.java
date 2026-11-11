package jp.cobolinsight.frontend.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveActivity;
import jp.cobolinsight.core.easytrieve.EasytrieveField;
import jp.cobolinsight.core.easytrieve.EasytrieveFile;
import jp.cobolinsight.core.easytrieve.EasytrieveProcedure;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.easytrieve.EasytrieveReport;
import jp.cobolinsight.core.easytrieve.EasytrieveStatement;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an Easytrieve Plus program into {@link EasytrieveProgram}.
 *
 * <p>The language has three parts, in this order: the environment (PARM), the library (FILE
 * statements and the field definitions under them, working storage) and the activities (JOB, SORT,
 * PROGRAM, SCREEN), each with its statements, then its procedures, then its reports. The reader
 * follows that order and nothing else: it does not check that a statement is one the compiler
 * would accept, so a program the compiler rejects still yields a model of what it declares.
 *
 * <p>A macro invocation ({@code %NAME}) is expanded in place from the member a
 * {@link MacroResolver} finds, or from an in-stream macro the program defines between MSTART and
 * MEND, with the parameters substituted the way the macro's own prototype names them. An invocation
 * whose member is not found is recorded and read past.
 */
public final class EasytrieveSourceParser {

    /** One physical line of source and where it stands. */
    public record SourceLine(String text, String file, int line) {
    }

    /** Finds the lines of the macro member a {@code %NAME} invocation names. */
    @FunctionalInterface
    public interface MacroResolver {

        MacroResolver NONE = name -> Optional.empty();

        Optional<List<SourceLine>> resolve(String name);
    }

    /** How deep one macro may invoke another before the expansion is given up as a loop. */
    private static final int MAX_MACRO_DEPTH = 10;

    private static final Set<String> ACTIVITIES = Set.of("JOB", "SORT", "PROGRAM", "SCREEN");

    /** The words that open a statement of the library section and so cannot be a field name. */
    private static final Set<String> LIBRARY_KEYWORDS = Set.of("FILE", "DEFINE", "PARM", "COPY",
            "SQL", "MACRO", "MSTART", "MEND", "DECLARE", "RECORD", "ELEMENT", "LOGICAL-RECORD",
            "IDD", "DLI", "IDMS", "ENDTABLE", "PUSH", "POP", "LIST", "NEWPAGE", "SKIP", "TITLE");

    private static final Set<String> ORGANISATIONS = Set.of("VS", "IS", "SQL", "DLI", "IDMS",
            "VIRTUAL", "PRINTER", "PUNCH", "CARD");

    private static final Set<String> RECORD_FORMATS =
            Set.of("F", "V", "U", "FB", "VB", "VBS", "FBA", "VBA", "FBM", "VBM");

    private static final Set<String> FIELD_TYPES = Set.of("A", "N", "P", "B", "U", "K", "M", "I");

    private static final Set<String> BLOCK_OPENERS = Set.of("IF", "DO", "CASE");
    private static final Set<String> BLOCK_CLOSERS = Set.of("END-IF", "END-DO", "END-CASE");
    private static final Set<String> BLOCK_BRANCHES =
            Set.of("ELSE", "ELSE-IF", "ELSEIF", "WHEN", "OTHERWISE");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final Pattern MACRO_HEAD = Pattern.compile("(?i)^MACRO(\\s|$)");

    /** The lines of a decoded source, numbered from 1 in the file they stand in. */
    public static List<SourceLine> linesOf(String text, String file) {
        List<SourceLine> lines = new ArrayList<>();
        int number = 1;
        for (String line : text.split("\\r\\n|\\r|\\n|\\u0085", -1)) {
            lines.add(new SourceLine(line, file, number++));
        }
        return lines;
    }

    /**
     * Whether the text is a macro member rather than a program: its first statement is the MACRO
     * prototype. A macro is read through the programs that invoke it.
     */
    public static boolean isMacroMember(String text) {
        for (String line : text.split("\\r\\n|\\r|\\n|\\u0085", -1)) {
            String body = line.strip();
            if (body.isEmpty() || body.charAt(0) == '*') {
                continue;
            }
            return MACRO_HEAD.matcher(body).find();
        }
        return false;
    }

    /** A program that stands in a member of its own, named after the member. */
    public EasytrieveProgram parse(DecodedSource source, MacroResolver macros) {
        return parse(memberNameOf(source.path()), source.path(),
                linesOf(source.text(), source.path()), Optional.empty(), macros);
    }

    /** A program given as lines: the in-stream source of a JCL step, or a member already split. */
    public EasytrieveProgram parse(String name, String sourceFile, List<SourceLine> lines,
            Optional<EasytrieveProgram.Host> host, MacroResolver macros) {
        return new Builder(name, sourceFile, host, macros).build(lines);
    }

    /** The member name a path stands for: the file name without its extension, upper case. */
    public static String memberNameOf(String path) {
        String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = file.lastIndexOf('.');
        return (dot <= 0 ? file : file.substring(0, dot)).toUpperCase(Locale.ROOT);
    }

    // ---- the reader of one program ----

    private static final class FileBuilder {
        final String name;
        String organisation = "SEQUENTIAL";
        Optional<String> recordFormat = Optional.empty();
        Optional<Integer> recordLength = Optional.empty();
        boolean table;
        boolean instreamTable;
        Optional<Integer> tableEntries = Optional.empty();
        List<String> options = List.of();
        final List<EasytrieveField> fields = new ArrayList<>();
        final List<String> tables = new ArrayList<>();
        SourcePosition position;
        int nextFree = 1;
        boolean rowsSkipped;

        FileBuilder(String name) {
            this.name = name;
        }

        EasytrieveFile build() {
            return new EasytrieveFile(name, organisation, recordFormat, recordLength, table,
                    instreamTable, tableEntries, options, fields, tables, position);
        }
    }

    private static final class ProcedureBuilder {
        final String name;
        final SourcePosition position;
        final List<EasytrieveStatement> statements = new ArrayList<>();

        ProcedureBuilder(String name, SourcePosition position) {
            this.name = name;
            this.position = position;
        }

        EasytrieveProcedure build() {
            return new EasytrieveProcedure(name, statements, position);
        }
    }

    private static final class ReportBuilder {
        final String name;
        final EasytrieveStatement header;
        final List<EasytrieveStatement> statements = new ArrayList<>();
        final List<EasytrieveProcedure> procedures = new ArrayList<>();

        ReportBuilder(String name, EasytrieveStatement header) {
            this.name = name;
            this.header = header;
        }

        EasytrieveReport build() {
            return new EasytrieveReport(name, header, statements, procedures,
                    positionOf(header));
        }
    }

    private static final class ActivityBuilder {
        final String kind;
        final EasytrieveStatement header;
        List<String> input = List.of();
        Optional<String> name = Optional.empty();
        final List<EasytrieveStatement> statements = new ArrayList<>();
        final List<EasytrieveProcedure> procedures = new ArrayList<>();
        final List<ReportBuilder> reports = new ArrayList<>();

        ActivityBuilder(String kind, EasytrieveStatement header) {
            this.kind = kind;
            this.header = header;
        }

        EasytrieveActivity build() {
            return new EasytrieveActivity(kind, header, input, name, statements, procedures,
                    reports.stream().map(ReportBuilder::build).toList(), positionOf(header));
        }
    }

    private static SourcePosition positionOf(EasytrieveStatement statement) {
        return new SourcePosition(statement.file(), statement.line(), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    private static final class Builder {

        private final String name;
        private final String sourceFile;
        private final Optional<EasytrieveProgram.Host> host;
        private final MacroResolver macros;

        private final Deque<StatementReader> readers = new ArrayDeque<>();
        private final Deque<String> expanding = new ArrayDeque<>();
        private final Map<String, List<SourceLine>> instreamMacros = new LinkedHashMap<>();

        private boolean macroMember;
        private boolean catalogFields;
        private final List<EasytrieveStatement> parms = new ArrayList<>();
        private final Map<String, FileBuilder> files = new LinkedHashMap<>();
        private final List<EasytrieveField> working = new ArrayList<>();
        private final Map<String, EasytrieveField> fieldsByName = new LinkedHashMap<>();
        private final List<ActivityBuilder> activities = new ArrayList<>();
        private final List<EasytrieveProgram.SqlBlock> sql = new ArrayList<>();
        private final List<EasytrieveProgram.Call> calls = new ArrayList<>();
        private final List<EasytrieveProgram.MacroUse> macroUses = new ArrayList<>();
        private final Map<String, String> fileAccess = new LinkedHashMap<>();

        private FileBuilder currentFile;
        private ActivityBuilder activity;
        private ReportBuilder report;
        private ProcedureBuilder procedure;
        private int depth;
        private SourcePosition firstPosition;

        Builder(String name, String sourceFile, Optional<EasytrieveProgram.Host> host,
                MacroResolver macros) {
            this.name = name;
            this.sourceFile = sourceFile;
            this.host = host;
            this.macros = macros;
        }

        EasytrieveProgram build(List<SourceLine> lines) {
            readers.push(new StatementReader(lines));
            for (StatementReader.Raw raw = next(); raw != null; raw = next()) {
                if (firstPosition == null) {
                    firstPosition = new SourcePosition(raw.file(), raw.line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET);
                }
                read(raw);
            }
            closeProcedure();
            deriveFileAccess();
            return new EasytrieveProgram(name, sourceFile, macroMember, host, parms,
                    files.values().stream().map(FileBuilder::build).toList(), working,
                    activities.stream().map(ActivityBuilder::build).toList(), sql, calls,
                    macroUses, fileAccess, catalogFields,
                    firstPosition == null ? SourcePosition.fileStart(sourceFile) : firstPosition);
        }

        /** The next statement of the innermost source being read, a finished macro popped off. */
        private StatementReader.Raw next() {
            while (!readers.isEmpty()) {
                StatementReader.Raw raw = readers.peek().next();
                if (raw != null) {
                    return raw;
                }
                readers.pop();
                if (!expanding.isEmpty()) {
                    expanding.pop();
                }
            }
            return null;
        }

        private void read(StatementReader.Raw raw) {
            List<String> words = raw.words();
            String keyword = words.get(0);
            if (keyword.startsWith("%") && keyword.length() > 1) {
                expandMacro(raw);
                return;
            }
            if (keyword.equals("MSTART")) {
                // The body is data until MEND: its parameters are not yet substituted.
                List<SourceLine> body = readers.peek().linesUntil("MEND");
                if (words.size() > 1) {
                    instreamMacros.put(words.get(1), body);
                }
                return;
            }
            if (keyword.equals("MACRO") && activities.isEmpty() && files.isEmpty()
                    && working.isEmpty() && parms.isEmpty()) {
                macroMember = expanding.isEmpty();
                return;
            }
            if (keyword.equals("MEND")) {
                return;
            }
            if (words.size() == 1 && isProcedureHead(keyword)) {
                StatementReader.Raw following = readers.peek().peek();
                if (following != null && following.words().get(0).equals("PROC")) {
                    readers.peek().next();
                    closeProcedure();
                    procedure = new ProcedureBuilder(keyword, new SourcePosition(raw.file(),
                            raw.line(), 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
                    depth = 0;
                    return;
                }
            }
            if (keyword.equals("END-PROC")) {
                closeProcedure();
                return;
            }
            if (ACTIVITIES.contains(keyword) && procedure == null && !isFieldDefinition(words)) {
                openActivity(raw);
                return;
            }
            if (keyword.equals("REPORT") && activity != null && procedure == null) {
                report = new ReportBuilder(words.size() > 1 ? words.get(1) : "REPORT",
                        statement(raw, 0));
                activity.reports.add(report);
                usePrinter(words);
                return;
            }
            if (activity == null) {
                readLibrary(raw);
                return;
            }
            readExecutable(raw);
        }

        /** A name that may stand in front of PROC: any single word that is not a block keyword. */
        private static boolean isProcedureHead(String word) {
            return !BLOCK_CLOSERS.contains(word) && !BLOCK_BRANCHES.contains(word)
                    && !word.equals("PROC") && !word.equals("STOP") && !word.equals("END-PROC");
        }

        /** {@code JOB 1 8 A}: a field that happens to be named like an activity. */
        private boolean isFieldDefinition(List<String> words) {
            return activities.isEmpty() && words.size() >= 4 && isLocation(words.get(1))
                    && NUMBER.matcher(words.get(2)).matches() && FIELD_TYPES.contains(words.get(3));
        }

        // ---- library ----

        private void readLibrary(StatementReader.Raw raw) {
            List<String> words = raw.words();
            String keyword = words.get(0);
            switch (keyword) {
                case "PARM" -> parms.add(statement(raw, 0));
                case "FILE" -> openFile(raw);
                case "DEFINE" -> defineField(words.subList(1, words.size()), raw);
                case "COPY" -> copyFields(words);
                case "SQL" -> readSql(raw);
                default -> {
                    if (!LIBRARY_KEYWORDS.contains(keyword) && words.size() >= 2
                            && isLocation(words.get(1))) {
                        defineField(words, raw);
                    }
                }
            }
        }

        private boolean isLocation(String word) {
            return NUMBER.matcher(word).matches() || word.equals("*") || word.equals("W")
                    || word.equals("S") || fieldsByName.containsKey(word);
        }

        private void openFile(StatementReader.Raw raw) {
            List<String> words = raw.words();
            if (words.size() < 2) {
                return;
            }
            FileBuilder file = new FileBuilder(words.get(1));
            file.position = new SourcePosition(raw.file(), raw.line(), 1,
                    SourcePosition.UNKNOWN_BYTE_OFFSET);
            file.options = List.copyOf(words.subList(2, words.size()));
            boolean organised = false;
            for (int i = 2; i < words.size(); i++) {
                String word = words.get(i);
                if (!organised && ORGANISATIONS.contains(word)) {
                    file.organisation = switch (word) {
                        case "VS" -> "VSAM";
                        case "IS" -> "ISAM";
                        default -> word;
                    };
                    organised = true;
                    if (word.equals("SQL")) {
                        i = readSqlFile(file, raw, words, i);
                    }
                } else if (RECORD_FORMATS.contains(word) && i + 1 < words.size()
                        && words.get(i + 1).equals("(")) {
                    file.recordFormat = Optional.of(word);
                    if (i + 2 < words.size() && NUMBER.matcher(words.get(i + 2)).matches()) {
                        file.recordLength = Optional.of(Integer.parseInt(words.get(i + 2)));
                    }
                } else if (word.equals("TABLE")) {
                    file.table = true;
                    String following = i + 1 < words.size() ? words.get(i + 1) : "";
                    if (following.equals("INSTREAM")) {
                        file.instreamTable = true;
                    } else if (NUMBER.matcher(following).matches()) {
                        file.tableEntries = Optional.of(Integer.parseInt(following));
                    }
                }
            }
            files.put(file.name, file);
            currentFile = file;
        }

        /**
         * {@code FILE name SQL (…)}: a full select, which is read as SQL, or the tables the file
         * stands for. Returns the index of the closing parenthesis.
         */
        private int readSqlFile(FileBuilder file, StatementReader.Raw raw, List<String> words,
                int at) {
            if (at + 1 >= words.size() || !words.get(at + 1).equals("(")) {
                return at;
            }
            int close = matchingParenthesis(words, at + 1);
            List<String> inside = words.subList(at + 2, close);
            if (!inside.isEmpty() && inside.get(0).equals("SELECT")) {
                int open = raw.text().indexOf('(');
                int end = raw.text().lastIndexOf(')');
                if (open >= 0 && end > open) {
                    sql.add(new EasytrieveProgram.SqlBlock(
                            raw.text().substring(open + 1, end).strip(), rangeOf(raw)));
                }
            } else {
                for (String word : inside) {
                    if (!word.equals("(") && !word.equals(")")) {
                        file.tables.add(word);
                    }
                }
            }
            return close;
        }

        private static int matchingParenthesis(List<String> words, int open) {
            int level = 0;
            for (int i = open; i < words.size(); i++) {
                if (words.get(i).equals("(")) {
                    level++;
                } else if (words.get(i).equals(")") && --level == 0) {
                    return i;
                }
            }
            return words.size() - 1;
        }

        /** {@code name location [length type [decimals]] [OCCURS n] [VALUE v] …}. */
        private void defineField(List<String> words, StatementReader.Raw raw) {
            if (words.size() < 2) {
                return;
            }
            String fieldName = words.get(0);
            String location = words.get(1);
            int at = 2;
            FileBuilder owner = currentFile;
            Optional<Integer> start = Optional.empty();
            boolean workingStorage = false;
            if (NUMBER.matcher(location).matches()) {
                start = Optional.of(Integer.parseInt(location));
            } else if (location.equals("*")) {
                int offset = 0;
                if (at < words.size() && words.get(at).equals("+") && at + 1 < words.size()
                        && NUMBER.matcher(words.get(at + 1)).matches()) {
                    offset = Integer.parseInt(words.get(at + 1));
                    at += 2;
                } else if (at < words.size() && words.get(at).matches("\\+\\d+")
                        && at + 1 < words.size() && NUMBER.matcher(words.get(at + 1)).matches()) {
                    // "* +4 6 N": the signed word is the offset only while a length follows it.
                    offset = Integer.parseInt(words.get(at).substring(1));
                    at++;
                }
                start = owner == null ? Optional.empty() : Optional.of(owner.nextFree + offset);
            } else if (location.equals("W") || location.equals("S")) {
                workingStorage = true;
            } else {
                // An overlay: the field starts where the named one does, in the same record.
                EasytrieveField base = fieldsByName.get(location);
                if (base == null) {
                    return;
                }
                int offset = 0;
                if (at < words.size() && words.get(at).matches("\\+\\d+")) {
                    offset = Integer.parseInt(words.get(at).substring(1));
                    at++;
                } else if (at + 1 < words.size() && words.get(at).equals("+")
                        && NUMBER.matcher(words.get(at + 1)).matches()) {
                    offset = Integer.parseInt(words.get(at + 1));
                    at += 2;
                }
                int shift = offset;
                start = base.start().map(position -> position + shift);
                workingStorage = base.working();
                owner = base.working() ? null : files.get(base.file());
            }
            if (owner == null) {
                workingStorage = true;
            }
            Optional<Integer> length = Optional.empty();
            String type = "";
            int decimals = 0;
            if (at < words.size() && NUMBER.matcher(words.get(at)).matches()) {
                length = Optional.of(Integer.parseInt(words.get(at)));
                at++;
                if (at < words.size() && FIELD_TYPES.contains(words.get(at))) {
                    type = words.get(at);
                    at++;
                    if (at < words.size() && NUMBER.matcher(words.get(at)).matches()) {
                        decimals = Integer.parseInt(words.get(at));
                        at++;
                    }
                }
            }
            int occurs = 1;
            boolean hasValue = false;
            for (int i = at; i < words.size(); i++) {
                String word = words.get(i);
                if (word.equals("OCCURS") && i + 1 < words.size()
                        && NUMBER.matcher(words.get(i + 1)).matches()) {
                    occurs = Integer.parseInt(words.get(i + 1));
                } else if (word.equals("VALUE")) {
                    hasValue = true;
                }
            }
            EasytrieveField field = new EasytrieveField(fieldName,
                    workingStorage ? "" : owner.name, location,
                    workingStorage ? Optional.empty() : start, length, type, decimals, occurs,
                    hasValue, new SourcePosition(raw.file(), raw.line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET));
            fieldsByName.put(fieldName, field);
            if (workingStorage) {
                working.add(field);
                return;
            }
            owner.fields.add(field);
            if (NUMBER.matcher(location).matches() || location.equals("*")) {
                if (field.end().isPresent()) {
                    owner.nextFree = field.end().get() + 1;
                }
            }
            if (owner.instreamTable && !owner.rowsSkipped && hasField(owner, "ARG")
                    && hasField(owner, "DESC") && !readers.isEmpty()) {
                // The rows of an in-stream table follow its two fields and are not statements.
                owner.rowsSkipped = true;
                readers.peek().linesUntil("ENDTABLE");
            }
        }

        private static boolean hasField(FileBuilder file, String fieldName) {
            return file.fields.stream().anyMatch(field -> field.name().equals(fieldName));
        }

        /** {@code COPY file}: the fields of another file, defined again in the current one. */
        private void copyFields(List<String> words) {
            if (currentFile == null || words.size() < 2) {
                return;
            }
            FileBuilder from = files.get(words.get(1));
            if (from == null || from == currentFile) {
                return;
            }
            for (EasytrieveField field : from.fields) {
                currentFile.fields.add(new EasytrieveField(field.name(), currentFile.name,
                        field.location(), field.start(), field.length(), field.type(),
                        field.decimals(), field.occurs(), field.hasValue(), field.position()));
            }
            currentFile.nextFree = Math.max(currentFile.nextFree, from.nextFree);
        }

        // ---- activities ----

        private void openActivity(StatementReader.Raw raw) {
            closeProcedure();
            EasytrieveStatement header = statement(raw, 0);
            activity = new ActivityBuilder(header.keyword(), header);
            activities.add(activity);
            report = null;
            currentFile = null;
            depth = 0;
            List<String> words = raw.words();
            int nameAt = words.indexOf("NAME");
            if (nameAt >= 0 && nameAt + 1 < words.size()) {
                activity.name = Optional.of(words.get(nameAt + 1));
            }
            if (header.keyword().equals("JOB")) {
                activity.input = jobInput(words);
            } else if (header.keyword().equals("SORT") && words.size() > 1) {
                activity.input = List.of(words.get(1));
            }
        }

        /** The files of {@code INPUT}: one name, NULL, SQL, or a parenthesised list with KEYs. */
        private static List<String> jobInput(List<String> words) {
            int at = words.indexOf("INPUT");
            if (at < 0 || at + 1 >= words.size()) {
                return List.of();
            }
            if (!words.get(at + 1).equals("(")) {
                return List.of(words.get(at + 1));
            }
            List<String> names = new ArrayList<>();
            int close = matchingParenthesis(words, at + 1);
            for (int i = at + 2; i < close; i++) {
                String word = words.get(i);
                if (word.equals("KEY")) {
                    // KEY field, or KEY (field …): neither is a file.
                    i = i + 1 < close && words.get(i + 1).equals("(")
                            ? matchingParenthesis(words, i + 1) : i + 1;
                } else if (!word.equals("(") && !word.equals(")")) {
                    names.add(word);
                }
            }
            return names;
        }

        private void readExecutable(StatementReader.Raw raw) {
            List<String> words = raw.words();
            String keyword = words.get(0);
            if (BLOCK_CLOSERS.contains(keyword) && depth > 0) {
                depth--;
            }
            int at = BLOCK_BRANCHES.contains(keyword) ? Math.max(0, depth - 1) : depth;
            EasytrieveStatement statement = statement(raw, at);
            if (BLOCK_OPENERS.contains(keyword)) {
                depth++;
            }
            switch (keyword) {
                case "DEFINE" -> defineField(words.subList(1, words.size()), raw);
                case "SQL" -> readSql(raw);
                case "CALL", "LINK", "TRANSFER" -> readCall(raw);
                default -> {
                }
            }
            if (procedure != null) {
                procedure.statements.add(statement);
            } else if (report != null) {
                report.statements.add(statement);
            } else {
                activity.statements.add(statement);
            }
        }

        private void closeProcedure() {
            if (procedure == null) {
                return;
            }
            EasytrieveProcedure built = procedure.build();
            if (report != null) {
                report.procedures.add(built);
            } else if (activity != null) {
                activity.procedures.add(built);
            }
            procedure = null;
            depth = 0;
        }

        private void readCall(StatementReader.Raw raw) {
            List<String> words = raw.words();
            if (words.size() < 2) {
                return;
            }
            String target = words.get(1);
            boolean quoted = target.startsWith("'");
            if (quoted) {
                target = target.replace("'", "").strip().toUpperCase(Locale.ROOT);
            }
            if (target.isEmpty()) {
                return;
            }
            // CALL names the program itself; LINK and TRANSFER take a literal or a field.
            boolean literal = words.get(0).equals("CALL") || quoted;
            calls.add(new EasytrieveProgram.Call(words.get(0), target, literal,
                    new SourcePosition(raw.file(), raw.line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }

        private void readSql(StatementReader.Raw raw) {
            String text = raw.text().strip();
            String body = text.length() > 3 ? text.substring(3).strip() : "";
            if (body.isEmpty()) {
                return;
            }
            if (body.regionMatches(true, 0, "INCLUDE", 0, 7)) {
                // The fields come from the Db2 catalogue: the table is named, its columns are not.
                catalogFields = true;
                Matcher from = Pattern.compile("(?i)\\bFROM\\s+([^\\s)]+)").matcher(body);
                if (from.find() && currentFile != null) {
                    currentFile.tables.add(from.group(1).toUpperCase(Locale.ROOT));
                }
                return;
            }
            sql.add(new EasytrieveProgram.SqlBlock(body, rangeOf(raw)));
        }

        private static SourceRange rangeOf(StatementReader.Raw raw) {
            return new SourceRange(
                    new SourcePosition(raw.file(), raw.line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET),
                    new SourcePosition(raw.file(), raw.endLine(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET));
        }

        private EasytrieveStatement statement(StatementReader.Raw raw, int at) {
            return new EasytrieveStatement(raw.words().get(0), raw.words(), raw.file(),
                    raw.line(), raw.endLine(), at);
        }

        // ---- macros ----

        private void expandMacro(StatementReader.Raw raw) {
            String macroName = raw.words().get(0).substring(1);
            SourcePosition at = new SourcePosition(raw.file(), raw.line(), 1,
                    SourcePosition.UNKNOWN_BYTE_OFFSET);
            List<SourceLine> body = instreamMacros.get(macroName);
            if (body == null) {
                body = macros.resolve(macroName).orElse(null);
            }
            if (body == null || expanding.contains(macroName)
                    || expanding.size() >= MAX_MACRO_DEPTH) {
                macroUses.add(new EasytrieveProgram.MacroUse(macroName, false, at));
                return;
            }
            macroUses.add(new EasytrieveProgram.MacroUse(macroName, true, at));
            expanding.push(macroName);
            readers.push(new StatementReader(substituted(body, raw.words())));
        }

        /**
         * The macro body with the invocation's values put in for its parameters. The prototype is
         * {@code MACRO [n] name… [keyword default]…}: the first {@code n} names are positional and
         * the rest come in pairs of a keyword and its default; with no count every name is
         * positional. The prototype line itself is left out of the result.
         */
        private static List<SourceLine> substituted(List<SourceLine> body, List<String> invocation) {
            int head = -1;
            for (int i = 0; i < body.size(); i++) {
                String line = body.get(i).text().strip();
                if (line.isEmpty() || line.charAt(0) == '*') {
                    continue;
                }
                if (MACRO_HEAD.matcher(line).find()) {
                    head = i;
                }
                break;
            }
            if (head < 0) {
                return body;
            }
            StatementReader.Raw prototype =
                    new StatementReader(List.of(body.get(head))).next();
            List<String> names = prototype == null ? List.of()
                    : prototype.words().subList(1, prototype.words().size());
            int positional = names.size();
            if (!names.isEmpty() && NUMBER.matcher(names.get(0)).matches()) {
                positional = Math.min(Integer.parseInt(names.get(0)), names.size() - 1);
                names = names.subList(1, names.size());
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < positional && i < names.size(); i++) {
                values.put(names.get(i), "");
            }
            for (int i = positional; i + 1 < names.size(); i += 2) {
                values.put(names.get(i), names.get(i + 1));
            }
            List<String> arguments = groupedArguments(invocation.subList(1, invocation.size()));
            int position = 0;
            for (int i = 0; i < arguments.size(); i++) {
                String argument = arguments.get(i);
                boolean keyword = values.containsKey(argument) && i + 1 < arguments.size()
                        && names.indexOf(argument) >= positional;
                if (keyword) {
                    values.put(argument, arguments.get(++i));
                } else if (position < positional) {
                    values.put(names.get(position++), argument);
                }
            }
            List<String> longestFirst = new ArrayList<>(values.keySet());
            longestFirst.sort(Comparator.comparingInt(String::length).reversed());
            List<SourceLine> out = new ArrayList<>();
            for (int i = head + 1; i < body.size(); i++) {
                SourceLine line = body.get(i);
                String text = line.text();
                if (text.indexOf('&') >= 0) {
                    for (String parameter : longestFirst) {
                        String value = Matcher.quoteReplacement(values.get(parameter));
                        text = text.replaceAll("(?i)&" + Pattern.quote(parameter) + "\\.?", value);
                    }
                }
                out.add(new SourceLine(text, line.file(), line.line()));
            }
            return out;
        }

        /** The words of an invocation with each parenthesised group made one value, parentheses off. */
        private static List<String> groupedArguments(List<String> words) {
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (!words.get(i).equals("(")) {
                    arguments.add(words.get(i));
                    continue;
                }
                int close = matchingParenthesis(words, i);
                arguments.add(String.join(" ", words.subList(i + 1, close)));
                i = close;
            }
            return arguments;
        }

        // ---- what the program does with each file ----

        private void usePrinter(List<String> words) {
            int at = words.indexOf("PRINTER");
            if (at >= 0 && at + 1 < words.size()) {
                use(words.get(at + 1), "WRITE");
            }
        }

        private void deriveFileAccess() {
            ActivityBuilder previous = null;
            for (ActivityBuilder each : activities) {
                List<String> words = each.header.words();
                if (each.kind.equals("SORT")) {
                    if (words.size() > 1) {
                        use(words.get(1), "READ");
                    }
                    int to = words.indexOf("TO");
                    if (to >= 0 && to + 1 < words.size()) {
                        use(words.get(to + 1), "WRITE");
                    }
                } else if (each.kind.equals("JOB")) {
                    if (each.input.isEmpty()) {
                        defaultInput(previous).ifPresent(file -> use(file, "READ"));
                    }
                    each.input.forEach(file -> use(file, "READ"));
                }
                previous = each;
            }
            List<EasytrieveStatement> all = new ArrayList<>();
            activities.forEach(each -> all.addAll(each.build().executableStatements()));
            for (EasytrieveStatement statement : all) {
                String target = statement.word(1);
                switch (statement.keyword()) {
                    case "GET", "READ", "POINT", "SEARCH" -> use(target, "READ");
                    case "PUT" -> use(target, "WRITE");
                    case "WRITE" -> use(target, "UPDATE");
                    // DISPLAY file …: the lines go to the named printer file instead of SYSPRINT.
                    case "DISPLAY" -> use(target, "WRITE");
                    default -> {
                    }
                }
            }
            for (FileBuilder file : files.values()) {
                if (file.table && !file.instreamTable) {
                    use(file.name, "READ");
                }
            }
        }

        /**
         * The file a JOB with no INPUT reads: the output of the SORT right before it, or else the
         * first file of the library that can be read in sequence.
         */
        private Optional<String> defaultInput(ActivityBuilder previous) {
            if (previous != null && previous.kind.equals("SORT")) {
                List<String> words = previous.header.words();
                int to = words.indexOf("TO");
                if (to >= 0 && to + 1 < words.size()) {
                    return Optional.of(words.get(to + 1));
                }
            }
            return files.values().stream()
                    .filter(file -> !file.table && !file.organisation.equals("PRINTER")
                            && !file.organisation.equals("PUNCH"))
                    .map(file -> file.name).findFirst();
        }

        /** Books one use of a declared file; reading and writing the same file comes to UPDATE. */
        private void use(String fileName, String access) {
            if (!files.containsKey(fileName)) {
                return;
            }
            String booked = fileAccess.get(fileName);
            fileAccess.put(fileName,
                    booked == null || booked.equals(access) ? access : "UPDATE");
        }
    }
}
