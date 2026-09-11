package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.TableUse;

/**
 * The SYSIN cards of the Db2 sample programs: {@code DSNTEP2} and {@code DSNTEP4} run free-form
 * SQL, {@code DSNTIAUL} unloads what a SELECT returns and {@code DSNTIAD} applies statements that
 * return no rows. All four run under the TSO launcher, so which of them a step runs is read off
 * the {@code RUN PROGRAM} card rather than off the EXEC statement.
 *
 * <p>What the tables are used for is read off the statement's first keyword. This is the shallow
 * scan the SQL frontend keeps for a statement it cannot parse; the frontend itself is not on this
 * module's path, so the scan is written again here rather than shared.
 */
final class SqlCards {

    private static final Set<String> PROGRAMS =
            Set.of("DSNTEP2", "DSNTEP4", "DSNTIAUL", "DSNTIAD");

    static final String CONTROL_DD = "SYSIN";

    /**
     * A table name, qualified or not. An SQL identifier opens with a letter, an underscore, a
     * national character or the quotation mark of a delimited name, never with a digit, so a number
     * standing where a name could stand — the {@code 1} of {@code SUBSTRING(X FROM 1 FOR 3)} — is no
     * table.
     */
    private static final String FIRST = "[A-Z@#$_\"]";
    private static final String NAME =
            FIRST + "[A-Z0-9@#$_\"]*(?:\\." + FIRST + "[A-Z0-9@#$_\"]*)?";

    /**
     * The tables a FROM or a JOIN names. A FROM may name several, separated by commas, and every
     * one of them is read.
     */
    private static final Pattern READ_FROM = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(" + NAME + "(?:\\s*,\\s*" + NAME + ")*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO = Pattern.compile(
            "^INSERT\\s+INTO\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE =
            Pattern.compile("^UPDATE\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM = Pattern.compile(
            "^DELETE\\s+FROM\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINITION = Pattern.compile(
            "^(CREATE|DROP|ALTER)\\s+\\w+\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);

    private SqlCards() {
    }

    static boolean isSqlProcessor(String program) {
        return PROGRAMS.contains(program.toUpperCase(Locale.ROOT));
    }

    /** Reads one SYSIN stream into the tables its statements name. */
    static void read(List<String> lines, List<TableUse> tableUses) {
        Set<String> seen = new LinkedHashSet<>();
        for (String statement : statements(lines)) {
            Matcher insert = INSERT_INTO.matcher(statement);
            Matcher update = UPDATE.matcher(statement);
            Matcher delete = DELETE_FROM.matcher(statement);
            Matcher definition = DEFINITION.matcher(statement);
            // Where the table the statement's own keyword names stands. That one occurrence is the
            // only one the read scan passes over — the FROM of a DELETE is the table being deleted
            // from — so the same table named again further on is a read all the same.
            int targetAt = -1;
            if (insert.find()) {
                add(tableUses, seen, insert.group(1), DatasetAccess.WRITE);
                targetAt = insert.start(1);
            } else if (delete.find()) {
                add(tableUses, seen, delete.group(1), DatasetAccess.DELETE);
                targetAt = delete.start(1);
            } else if (update.find()) {
                add(tableUses, seen, update.group(1), DatasetAccess.UPDATE);
                targetAt = update.start(1);
            } else if (definition.find()) {
                add(tableUses, seen, definition.group(2), switch (definition.group(1)
                        .toUpperCase(Locale.ROOT)) {
                    case "CREATE" -> DatasetAccess.CREATE;
                    case "DROP" -> DatasetAccess.DELETE;
                    default -> DatasetAccess.UPDATE;
                });
                targetAt = definition.start(2);
            }
            // The scan runs whatever the statement's own keyword was, because a
            // CREATE ... AS SELECT reads every table it selects from.
            Matcher read = READ_FROM.matcher(statement);
            while (read.find()) {
                boolean target = read.start(1) == targetAt;
                for (String name : read.group(1).split(",")) {
                    if (!target && !name.isBlank()) {
                        add(tableUses, seen, name.strip(), DatasetAccess.READ);
                    }
                    target = false;
                }
            }
        }
    }

    private static void add(List<TableUse> tableUses, Set<String> seen, String name,
            DatasetAccess access) {
        String table = name.toUpperCase(Locale.ROOT);
        if (seen.add(table + " " + access)) {
            tableUses.add(new TableUse(table, access));
        }
    }

    /** The statements of a stream: the cards without their comments, cut at each semicolon. */
    private static List<String> statements(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String card = Cards.withoutComment(line);
            if (!card.isBlank()) {
                text.append(text.length() == 0 ? "" : " ").append(card.strip());
            }
        }
        List<String> statements = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        boolean literal = false;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\'') {
                literal = !literal;
            }
            if (c == ';' && !literal) {
                addStatement(statements, statement);
                continue;
            }
            statement.append(c);
        }
        addStatement(statements, statement);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder statement) {
        String text = statement.toString().strip();
        statement.setLength(0);
        if (!text.isEmpty()) {
            statements.add(text);
        }
    }
}
