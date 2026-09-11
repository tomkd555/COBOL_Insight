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
 * The SYSIN cards of a Db2 utility step. {@code DSNUTILB} reads one or more utility statements,
 * each opening with its verb and naming the table space or the table it works on.
 *
 * <p>The cards are free-form and carry no continuation mark, so the whole stream is read as one text
 * and cut at each verb that opens a card; what stands between two verbs belongs to the first of
 * them. A verb in the middle of a card is an operand of the statement it stands in — {@code REORG}
 * names a utility to run, {@code COPY} an option of LOAD — and starts nothing.
 */
final class Dsnutilb {

    static final String PROGRAM = "DSNUTILB";

    static final String CONTROL_DD = "SYSIN";

    /** The utility verbs, and how each of them uses what it names. */
    private static final List<String> VERBS = List.of("LOAD", "UNLOAD", "REORG", "RUNSTATS",
            "COPY", "RECOVER", "CHECK", "REBUILD", "MODIFY", "QUIESCE", "REPORT", "STOSPACE");

    /**
     * A verb standing on its own. The lookbehind is what keeps a qualified table name whose second
     * half happens to be a verb ({@code SCHEMA.REPORT}) from being read as a statement of its own:
     * a word boundary alone matches after the dot.
     */
    private static final Pattern VERB = Pattern.compile(
            "(?<![A-Za-z0-9@#$_.])(" + String.join("|", VERBS) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code TABLESPACE db.ts}, {@code INDEXSPACE db.ix}, {@code TABLE x} and {@code INTO TABLE x},
     * with a name after them.
     */
    private static final Pattern NAMED = Pattern.compile(
            "\\b(?:TABLESPACE|INDEXSPACE|TABLE)\\s+([A-Z0-9@#$_]+(?:\\.[A-Z0-9@#$_]+)?)",
            Pattern.CASE_INSENSITIVE);

    private Dsnutilb() {
    }

    /** Reads one SYSIN stream into the tables its utility statements name. */
    static void read(List<String> lines, List<TableUse> tableUses) {
        Cards.Joined stream = Cards.joined(lines);
        String text = stream.text();
        Matcher verbs = VERB.matcher(text);
        List<int[]> statements = new ArrayList<>();
        while (verbs.find()) {
            if (stream.cardStarts().contains(verbs.start())) {
                statements.add(new int[] {verbs.start(), verbs.end()});
            }
        }
        for (int at = 0; at < statements.size(); at++) {
            int[] verb = statements.get(at);
            int end = at + 1 < statements.size() ? statements.get(at + 1)[0] : text.length();
            DatasetAccess access = access(text.substring(verb[0], verb[1]));
            Set<String> named = new LinkedHashSet<>();
            Matcher matcher = NAMED.matcher(text.substring(verb[1], end));
            while (matcher.find()) {
                named.add(matcher.group(1).toUpperCase(Locale.ROOT));
            }
            named.forEach(name -> tableUses.add(new TableUse(name, access)));
        }
    }

    private static DatasetAccess access(String verb) {
        return switch (verb.toUpperCase(Locale.ROOT)) {
            case "LOAD" -> DatasetAccess.WRITE;
            case "UNLOAD" -> DatasetAccess.READ;
            case "REORG" -> DatasetAccess.UPDATE;
            case "RUNSTATS", "COPY", "REPORT", "CHECK" -> DatasetAccess.READ;
            default -> DatasetAccess.UNKNOWN;
        };
    }
}
