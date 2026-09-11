package jp.cobolinsight.frontend.jcl.instream;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;

/**
 * The control cards of a sort step. DFSORT and the products that stand in for it read SYSIN;
 * ICETOOL reads TOOLIN and names its input and output DD statements on each operator card.
 *
 * <p>Only the cards that name a DD statement are read here. Which DD a sort reads and writes
 * without being told stands in {@link UtilityDdRoles}.
 *
 * <p>A DD two cards name differently is read and written both, which is one access and not two:
 * such a DD is recorded as an UPDATE rather than letting whichever card was read last decide.
 */
final class SortCards {

    private static final Set<String> PROGRAMS =
            Set.of("SORT", "ICEMAN", "SYNCSORT", "DFSORT", "ICETOOL");

    private static final String ICETOOL = "ICETOOL";

    /** {@code OUTFIL FNAMES=(OUT2,OUT3)} or {@code FNAMES=OUT1}: the DD statements written to. */
    private static final Pattern FNAMES = Pattern.compile(
            "\\bFNAMES=(\\(([^)]*)\\)|[A-Z0-9@#$]+)", Pattern.CASE_INSENSITIVE);

    /** {@code JOINKEYS FILE=F1} and the older {@code F1=dd}: the DD statements joined. */
    private static final Pattern JOINKEYS = Pattern.compile(
            "\\bJOINKEYS\\b[^\\n]*?\\b(FILE|F1|F2)=([A-Z0-9@#$]+)", Pattern.CASE_INSENSITIVE);

    /** What DFSORT calls the DD of a join file: SORTJNF1 for F1, SORTJNF2 for F2. */
    private static final String JOIN_DD_PREFIX = "SORTJN";

    /** An ICETOOL operator: {@code COPY FROM(IN) TO(OUT) USING(CTL1)}. */
    private static final Pattern FROM =
            Pattern.compile("\\bFROM\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TO =
            Pattern.compile("\\bTO\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USING =
            Pattern.compile("\\bUSING\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** What ICETOOL calls the DD holding the control cards a USING() names. */
    private static final String USING_SUFFIX = "CNTL";

    private SortCards() {
    }

    static boolean isSort(String program) {
        return PROGRAMS.contains(program);
    }

    /** SYSIN for the sort products, TOOLIN for ICETOOL. */
    static String controlDd(String program) {
        return ICETOOL.equals(program) ? "TOOLIN" : "SYSIN";
    }

    /** Reads one control stream into the roles its cards give the step's DD statements. */
    static void read(String program, List<String> lines, Map<String, DatasetAccess> ddRoles) {
        for (String card : Cards.sortControl(lines)) {
            if (ICETOOL.equals(program)) {
                put(ddRoles, FROM.matcher(card), DatasetAccess.READ);
                put(ddRoles, TO.matcher(card), DatasetAccess.WRITE);
                Matcher using = USING.matcher(card);
                while (using.find()) {
                    role(ddRoles, using.group(1).strip() + USING_SUFFIX, DatasetAccess.READ);
                }
                continue;
            }
            put(ddRoles, FNAMES.matcher(card), DatasetAccess.WRITE);
            joined(ddRoles, JOINKEYS.matcher(card));
        }
    }

    /**
     * The DD statements a JOINKEYS card joins. {@code FILE=F1} names the join file rather than a
     * DD, and DFSORT reads that file through SORTJNF1 (F2 through SORTJNF2); only the older
     * {@code F1=ddname} form names a DD of the step itself.
     */
    private static void joined(Map<String, DatasetAccess> ddRoles, Matcher matcher) {
        while (matcher.find()) {
            String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
            String value = matcher.group(2).strip().toUpperCase(Locale.ROOT);
            if (!keyword.equals("FILE")) {
                role(ddRoles, value, DatasetAccess.READ);
            } else if (value.equals("F1") || value.equals("F2")) {
                role(ddRoles, JOIN_DD_PREFIX + value, DatasetAccess.READ);
            }
        }
    }

    /** Every DD name the match names, which may be one or a parenthesised list of them. */
    private static void put(Map<String, DatasetAccess> ddRoles, Matcher matcher,
            DatasetAccess access) {
        while (matcher.find()) {
            for (String name : matcher.group(1).replaceAll("[()]", "").split(",")) {
                if (!name.isBlank()) {
                    role(ddRoles, name, access);
                }
            }
        }
    }

    /** One DD's role, two cards that name it differently making it an UPDATE. */
    private static void role(Map<String, DatasetAccess> ddRoles, String ddName,
            DatasetAccess access) {
        ddRoles.merge(ddName.strip().toUpperCase(Locale.ROOT), access,
                (was, now) -> was == now ? was : DatasetAccess.UPDATE);
    }
}
