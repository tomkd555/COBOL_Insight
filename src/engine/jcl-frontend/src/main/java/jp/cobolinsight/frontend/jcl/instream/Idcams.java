package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetUse;

/**
 * The SYSIN cards of an {@code IDCAMS} step: the access method services commands that define,
 * copy, print, rename and delete data sets. A command carries on where its line ends with a
 * hyphen, and names its objects either by data set name or through a DD statement of the step.
 */
final class Idcams {

    static final String PROGRAM = "IDCAMS";

    static final String CONTROL_DD = "SYSIN";

    /**
     * {@code DEFINE CLUSTER (NAME(x) …) DATA (NAME(y) …)}: every object the command creates. A
     * VSAM cluster names its data and index components under names of their own, and all of them
     * come into being together, so every NAME the card holds is one the step creates.
     */
    private static final Pattern DEFINE = Pattern.compile(
            "^DEF(?:INE)?\\s+(?:CLUSTER|GDG|GENERATIONDATAGROUP|AIX|ALTERNATEINDEX|PATH|ALIAS"
                    + "|NONVSAM|PAGESPACE|USERCATALOG)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile(
            "\\bNAME\\s*\\(\\s*('[^']*'|[^\\s)]+)", Pattern.CASE_INSENSITIVE);

    /** DELETE names one data set, or a parenthesised list of them. */
    private static final Pattern DELETE =
            Pattern.compile("^DEL(?:ETE)?\\b(.*)$", Pattern.CASE_INSENSITIVE);

    /** {@code ALTER x NEWNAME(y)}: x is changed, and a rename brings y into being. */
    private static final Pattern ALTER =
            Pattern.compile("^ALTER\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEWNAME = Pattern.compile(
            "\\bNEWNAME\\s*\\(\\s*('[^']*'|[^\\s)]+)", Pattern.CASE_INSENSITIVE);

    /** REPRO and PRINT name their input and output either by DD or by data set name. */
    private static final Pattern MOVE =
            Pattern.compile("^(REPRO|PRINT)\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEYWORD = Pattern.compile(
            "\\b(INFILE|OUTFILE|INDATASET|OUTDATASET)\\s*\\(\\s*('[^']*'|[^\\s)]+)",
            Pattern.CASE_INSENSITIVE);

    private Idcams() {
    }

    /** Reads one SYSIN stream into the data sets and DD statements its commands name. */
    static void read(List<String> lines, List<DatasetUse> datasetUses,
            Map<String, DatasetAccess> ddRoles) {
        for (String card : Cards.continued(lines)) {
            if (DEFINE.matcher(card).find()) {
                for (String name : named(NAME, card)) {
                    datasetUses.add(new DatasetUse(name, DatasetAccess.CREATE));
                }
                continue;
            }
            Matcher delete = DELETE.matcher(card);
            if (delete.matches()) {
                for (String name : Cards.operandNames(delete.group(1))) {
                    datasetUses.add(new DatasetUse(name, DatasetAccess.DELETE));
                }
                continue;
            }
            Matcher alter = ALTER.matcher(card);
            if (alter.matches()) {
                for (String name : Cards.operandNames(alter.group(1))) {
                    datasetUses.add(new DatasetUse(name, DatasetAccess.UPDATE));
                }
                for (String name : named(NEWNAME, card)) {
                    datasetUses.add(new DatasetUse(name, DatasetAccess.CREATE));
                }
                continue;
            }
            Matcher move = MOVE.matcher(card);
            if (move.matches()) {
                move(move.group(2), datasetUses, ddRoles);
            }
        }
    }

    /** Every name the card names through that keyword, in the order it names them. */
    private static List<String> named(Pattern keyword, String card) {
        List<String> names = new ArrayList<>();
        Matcher matcher = keyword.matcher(card);
        while (matcher.find()) {
            names.add(Cards.unquote(matcher.group(1)));
        }
        return names;
    }

    private static void move(String operands, List<DatasetUse> datasetUses,
            Map<String, DatasetAccess> ddRoles) {
        Matcher matcher = KEYWORD.matcher(operands);
        while (matcher.find()) {
            String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
            String name = Cards.unquote(matcher.group(2));
            DatasetAccess access =
                    keyword.startsWith("OUT") ? DatasetAccess.WRITE : DatasetAccess.READ;
            if (keyword.endsWith("FILE")) {
                ddRoles.put(name.toUpperCase(Locale.ROOT), access);
            } else {
                datasetUses.add(new DatasetUse(name, access));
            }
        }
    }
}
