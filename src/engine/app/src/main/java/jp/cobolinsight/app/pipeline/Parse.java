package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.JclMemberResolver;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.frontend.bms.BmsModelMapper;
import jp.cobolinsight.frontend.bms.BmsParseError;
import jp.cobolinsight.frontend.bms.BmsParseResult;
import jp.cobolinsight.frontend.bms.BmsSourceParser;
import jp.cobolinsight.frontend.sql.SqlScriptSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses every decoded unit with the frontend for its kind. A file that fails to parse becomes a
 * finding and is recorded as unanalysable, and the run carries on with the rest — one unparsable
 * source must not cost the analysis of every other.
 */
public final class Parse implements Step {

    /**
     * The head of a routine definition, down to the name it gives. A script's other CREATE
     * statements are DDL the call graph reads as a declaration; these three define something that
     * runs, so the graph gives each a node of its own.
     */
    private static final Pattern ROUTINE_DEFINITION = Pattern.compile(
            "^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|TRIGGER)\\s+([^\\s(]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * One statement of a routine body, from the verb that names it. The control flow around it —
     * {@code IF … THEN}, {@code ELSE}, {@code WHEN} — is not SQL the frontend reads, and it stands
     * in front of the statement it guards, so the text handed over starts at the verb.
     */
    private static final Pattern ROUTINE_BODY_STATEMENT = Pattern.compile(
            "\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|CALL|SET|VALUES|DECLARE|OPEN|FETCH|CLOSE"
                    + "|TRUNCATE|LOCK)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public void apply(SourceSet s) {
        CobolParser cobol = EngineWiring.cobolParser();
        JclParser jcl = EngineWiring.jclParser();
        BmsSourceParser bms = EngineWiring.bmsParser();
        JclMembers members = new JclMembers(s);
        // One diagnostic reaches the report once, however many JCL files expand the member that
        // raised it: the same member read from two jobs would otherwise write two identical rows.
        Set<Map.Entry<String, Finding>> reported = new LinkedHashSet<>();
        for (SourceUnit unit : s.units()) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded == null) {
                continue;
            }
            switch (unit.kind()) {
                case COBOL -> parseCobol(s, cobol, unit, decoded);
                case JCL -> parseJcl(s, jcl, unit, decoded, members, reported);
                case BMS -> parseBms(s, bms, unit, decoded);
                case SQL -> parseSqlScript(s, unit, decoded);
                case COPYBOOK -> {
                    // Copybooks are analysed through the programs that copy them; on their own they
                    // contribute only their text.
                }
            }
        }
        diagnoseUnexpandedMembers(s, jcl, members, reported);
    }

    private static void parseCobol(SourceSet s, CobolParser parser, SourceUnit unit,
            DecodedSource decoded) {
        ParseOutcome<CobolSemanticModel> outcome =
                parser.parse(decoded, s.copybookSearchPaths());
        if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
            s.addFinding(unit.relPath(), failure.finding());
            s.unanalyzable().put(unit.relPath(), failure.finding().message());
            return;
        }
        s.programsByPath().put(unit.relPath(), outcome.value().orElseThrow());
    }

    private static void parseJcl(SourceSet s, JclParser parser, SourceUnit unit,
            DecodedSource decoded, JclMembers members,
            Set<Map.Entry<String, Finding>> reported) {
        if (parser.isMember(decoded)) {
            // A catalogued PROC or an INCLUDE member found in the folder: its steps belong to the
            // jobs that call it, and it is read on its own only if no job called it.
            return;
        }
        ParseOutcome<List<JclJobModel>> outcome = parser.parse(decoded, members.forUnit(unit));
        if (outcome instanceof ParseOutcome.Failure<List<JclJobModel>> failure) {
            s.addFinding(unit.relPath(), failure.finding());
            s.unanalyzable().put(unit.relPath(), failure.finding().message());
            return;
        }
        List<JclJobModel> jobs = outcome.value().orElseThrow();
        // A statement the parser could not read is reported where it stands; the job around it is
        // kept, so the analysis loses that one statement and nothing else.
        jobs.stream().flatMap(job -> job.diagnostics().stream())
                .forEach(finding -> record(s, members, unit.relPath(), finding, reported));
        s.jobsByPath().put(unit.relPath(), jobs);
    }

    /**
     * Reads every member no job of the run expanded, once, for the diagnostics it holds. The model
     * it would build is thrown away: a member's steps belong to the jobs that call it, and nothing
     * called this one, but its syntax errors and its directive lines are still worth reporting.
     */
    private static void diagnoseUnexpandedMembers(SourceSet s, JclParser parser,
            JclMembers members, Set<Map.Entry<String, Finding>> reported) {
        // Keyed the way JclMembers keys the same paths, so two spellings of one file agree.
        Set<String> expanded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (List<JclJobModel> jobs : s.jobsByPath().values()) {
            for (JclJobModel job : jobs) {
                job.members().forEach(file -> expanded.add(Paths.normalisedKey(file)));
            }
        }
        for (SourceUnit unit : s.unitsOf(AssetKind.JCL)) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded == null || !parser.isMember(decoded)
                    || expanded.contains(Paths.normalisedKey(unit.absPath().toString()))) {
                continue;
            }
            for (Finding finding : parser.diagnose(decoded)) {
                record(s, members, unit.relPath(), finding, reported);
            }
        }
    }

    /**
     * Records one JCL diagnostic against the asset it belongs to, once for the whole run. A
     * diagnostic raised inside an expanded member belongs to the member's own source. A member the
     * walk never saw — one reached through {@code --proc-path} — has no source row of its own, so
     * the row falls back to the calling JCL and the message names the member file, without which
     * the row would carry the member's line against the job's source.
     */
    private static void record(SourceSet s, JclMembers members, String relPath, Finding finding,
            Set<Map.Entry<String, Finding>> reported) {
        Optional<String> asset = members.relPathOf(finding.location().file());
        Finding reportable = asset.isPresent() ? finding : named(finding);
        String target = asset.orElse(relPath);
        if (reported.add(Map.entry(target, reportable))) {
            s.addFinding(target, reportable);
        }
    }

    /** The finding with the file name of the member it was raised in put in front of it. */
    private static Finding named(Finding finding) {
        String name;
        try {
            name = Path.of(finding.location().file()).getFileName().toString();
        } catch (InvalidPathException e) {
            return finding;
        }
        return new Finding(finding.ruleId(), finding.level(),
                "メンバー " + name + ": " + finding.message(), finding.location(),
                finding.codeFlows(), finding.fixes());
    }

    private static void parseBms(SourceSet s, BmsSourceParser parser, SourceUnit unit,
            DecodedSource decoded) {
        BmsParseResult result = parser.parse(decoded.text());
        for (BmsParseError error : result.errors()) {
            // BmsParseError columns are zero-based; SourcePosition columns are one-based.
            s.addFinding(unit.relPath(), Finding.parseFailure(
                    new SourcePosition(unit.relPath(), Math.max(1, error.line()),
                            error.column() + 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                    error.message()));
        }
        List<BmsMapset> mapsets = BmsModelMapper.toEngineApi(result, unit.relPath());
        s.mapsetsByPath().put(unit.relPath(), mapsets);
    }

    /**
     * Splits an SQL script into its statements and reads each one with the same frontend an
     * {@code EXEC SQL} block of a program goes through. A statement the grammar refuses comes back
     * degraded, and {@link Semantic} reports it where it stands; nothing in a script makes the file
     * itself unanalysable. A string literal left open is reported before the split, because it
     * takes the rest of the script with it and the shorter list of statements says nothing of why.
     *
     * <p>Gated on {@link Needs#SQL}: a run whose rules never look at SQL has no use for the
     * statements, and {@code scan}, which writes them, always asks for it.
     */
    private static void parseSqlScript(SourceSet s, SourceUnit unit, DecodedSource decoded) {
        if (!s.requires(Needs.SQL)) {
            return;
        }
        SqlScriptSplitter.unclosedLiteralLine(decoded.text()).ifPresent(line ->
                s.addFinding(unit.relPath(), Finding.of(Finding.SQL_SYNTAX_RULE_ID,
                        FindingLevel.WARNING,
                        "閉じていない文字列定数があります。これより後ろの SQL 文は読み取れません。",
                        new SourcePosition(decoded.path(), line, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET))));
        SqlParser parser = EngineWiring.sqlParser();
        List<SqlStatementModel> statements = new ArrayList<>();
        for (SqlScriptSplitter.Statement split
                : SqlScriptSplitter.split(decoded.text(), byteOffsetsOf(decoded))) {
            SourceRange range = rangeOf(decoded.path(), split);
            analyse(s, parser, unit, split.text(), range)
                    .ifPresent(statement -> {
                        statements.add(statement);
                        routineOf(s, parser, unit, decoded.path(), split);
                    });
        }
        if (!statements.isEmpty()) {
            s.sqlByPath().put(unit.relPath(), statements);
        }
    }

    /**
     * The byte offset of each character of a decoded source, which is what the splitter needs to read
     * the card-image columns of a script written in a double-byte code page. The index one past the
     * last character answers with the whole byte length, the way a line's end asks for it.
     */
    private static java.util.function.IntUnaryOperator byteOffsetsOf(DecodedSource decoded) {
        int characters = decoded.text().length();
        int bytes = decoded.originalBytes().length;
        return index -> index < characters ? decoded.byteOffsetAt(index) : bytes;
    }

    /**
     * The routine one statement of a script defines, recorded with the statements of its body. The
     * body is read again because the whole CREATE is one statement to the grammar, while the tables
     * the routine touches stand in the statements inside it. A routine whose body is a single
     * {@code RETURN} expression rather than a compound statement is recorded with no body.
     */
    private static void routineOf(SourceSet s, SqlParser parser, SourceUnit unit, String file,
            SqlScriptSplitter.Statement split) {
        Matcher head = ROUTINE_DEFINITION.matcher(split.text());
        if (!head.find()) {
            return;
        }
        List<SqlStatementModel> body = new ArrayList<>();
        collectBody(s, parser, unit, file, split.text(), split.startLine(), body);
        s.sqlRoutines().add(new SqlRoutineDefinition(head.group(1), unit.relPath(),
                split.startLine(), body));
    }

    /**
     * The statements of one compound block, with a block nested inside it read the same way. Handing
     * a nested {@code BEGIN … END} to the frontend whole would book the access of every statement in
     * it under the kind of the blob's first word, so the block is opened instead.
     *
     * <p>Each statement keeps the line it stands on in the script, which is what its findings and
     * its edges are reported at.
     */
    private static void collectBody(SourceSet s, SqlParser parser, SourceUnit unit, String file,
            String statement, int startLine, List<SqlStatementModel> into) {
        SqlScriptSplitter.CompoundBody compound =
                SqlScriptSplitter.compoundBody(statement).orElse(null);
        if (compound == null) {
            return;
        }
        int bodyLine = startLine + compound.lineOffset();
        for (SqlScriptSplitter.Statement inner : SqlScriptSplitter.split(compound.text())) {
            int line = bodyLine + inner.startLine() - 1;
            if (SqlScriptSplitter.compoundBody(inner.text()).isPresent()) {
                collectBody(s, parser, unit, file, inner.text(), line, into);
                continue;
            }
            Matcher verb = ROUTINE_BODY_STATEMENT.matcher(inner.text());
            if (verb.find()) {
                analyse(s, parser, unit, verb.group(),
                        lineRange(file, line + lineBreaksBefore(inner.text(), verb.start())))
                        .ifPresent(into::add);
            }
        }
    }

    /** How many line breaks stand before an offset, which is how far down from its line 1 it is. */
    private static int lineBreaksBefore(String text, int offset) {
        int breaks = 0;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= offset || text.charAt(i + 1) != '\n'))) {
                breaks++;
            }
        }
        return breaks;
    }

    /** One whole line as a range, which is the position a statement inside a routine is reported at. */
    private static SourceRange lineRange(String file, int line) {
        SourcePosition start = new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(start, start);
    }

    /** One span of script text read as an SQL block. Empty where the frontend refused it outright. */
    private static Optional<SqlStatementModel> analyse(SourceSet s, SqlParser parser,
            SourceUnit unit, String text, SourceRange range) {
        ParseOutcome<SqlStatementModel> outcome = parser.parse(
                new EmbeddedBlock(EmbeddedBlockKind.SQL, text, Map.of(), range));
        if (outcome instanceof ParseOutcome.Failure<SqlStatementModel> failure) {
            s.addFinding(unit.relPath(), failure.finding());
            return Optional.empty();
        }
        return outcome.value();
    }

    private static SourceRange rangeOf(String file, SqlScriptSplitter.Statement split) {
        return new SourceRange(
                new SourcePosition(file, split.startLine(), split.startColumn(),
                        SourcePosition.UNKNOWN_BYTE_OFFSET),
                new SourcePosition(file, split.endLine(), split.endColumn(),
                        SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    /**
     * Where a JCL's PROC and INCLUDE members are looked up: the JCL's own directory first, then
     * anywhere the walk found JCL, then the directories {@code --proc-path} names. The walk takes
     * assets wherever they sit, so no fixed layout can be assumed, and a member outside the asset
     * folder is decoded here with the same code-page detection as every other asset.
     */
    private static final class JclMembers {

        /** The extensions a member file may carry, tried in this order after the bare name. */
        private static final List<String> SUFFIXES =
                List.of("", ".jcl", ".JCL", ".proc", ".PROC", ".prc", ".PRC", ".inc", ".INC");

        private final SourceSet set;
        private final Map<String, List<SourceUnit>> unitsByMemberName = new HashMap<>();
        /** Keyed the way {@link Persist} keys the same paths, so both agree on one spelling. */
        private final Map<String, String> relPathByAbsPath =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, Optional<DecodedSource>> fromLibraries = new LinkedHashMap<>();

        JclMembers(SourceSet set) {
            this.set = set;
            for (SourceUnit unit : set.unitsOf(AssetKind.JCL)) {
                unitsByMemberName.computeIfAbsent(memberNameOf(unit.fileName()),
                        key -> new ArrayList<>()).add(unit);
                relPathByAbsPath.put(Paths.normalisedKey(unit.absPath().toString()),
                        unit.relPath());
            }
        }

        /** The asset a finding's file names, empty when the walk never saw that file. */
        Optional<String> relPathOf(String file) {
            try {
                return Optional.ofNullable(relPathByAbsPath.get(Paths.normalisedKey(file)));
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }

        JclMemberResolver forUnit(SourceUnit unit) {
            Path directory = unit.absPath().getParent();
            return memberName -> resolve(memberName, directory);
        }

        private Optional<DecodedSource> resolve(String memberName, Path directory) {
            List<SourceUnit> candidates =
                    unitsByMemberName.getOrDefault(memberNameOf(memberName), List.of());
            DecodedSource anywhere = null;
            for (SourceUnit candidate : candidates) {
                DecodedSource decoded = set.decoded().get(candidate.relPath());
                if (decoded == null) {
                    continue;
                }
                if (candidate.absPath().getParent() != null
                        && candidate.absPath().getParent().equals(directory)) {
                    return Optional.of(decoded);
                }
                if (anywhere == null) {
                    anywhere = decoded;
                }
            }
            return anywhere != null ? Optional.of(anywhere)
                    : fromLibraries.computeIfAbsent(memberNameOf(memberName),
                            key -> fromLibraries(memberName, key));
        }

        /**
         * The member file under a {@code --proc-path} directory. The name is tried as the JCL
         * wrote it and in upper case, because a case-sensitive filesystem answers only to the
         * spelling on disk, and a candidate that cannot be read leaves the search to carry on.
         */
        private Optional<DecodedSource> fromLibraries(String written, String uppercase) {
            CharsetProvider charsets = EngineWiring.charsetProvider();
            Set<String> names = new LinkedHashSet<>(List.of(written, uppercase));
            for (Path library : set.options().procedureLibraryPaths()) {
                for (String name : names) {
                    for (String suffix : SUFFIXES) {
                        Path candidate = library.resolve(name + suffix);
                        if (!Files.isRegularFile(candidate)) {
                            continue;
                        }
                        Optional<DecodedSource> decoded = read(charsets, candidate);
                        if (decoded.isPresent()) {
                            return decoded;
                        }
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<DecodedSource> read(CharsetProvider charsets, Path candidate) {
            try {
                byte[] bytes = Files.readAllBytes(candidate);
                String override = set.options().codepageOverrides()
                        .get(candidate.getFileName().toString());
                String path = candidate.toString();
                return Optional.of(override == null ? charsets.decode(path, bytes)
                        : charsets.decode(path, bytes, override));
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("警告: " + candidate + " を読み込めませんでした（"
                        + Failures.describe(e) + "）。このメンバーは展開しません。");
                return Optional.empty();
            }
        }

        /** A member is named by the file name without its extension, whatever its case. */
        private static String memberNameOf(String fileName) {
            int dot = fileName.lastIndexOf('.');
            return (dot <= 0 ? fileName : fileName.substring(0, dot)).toUpperCase(Locale.ROOT);
        }
    }
}
