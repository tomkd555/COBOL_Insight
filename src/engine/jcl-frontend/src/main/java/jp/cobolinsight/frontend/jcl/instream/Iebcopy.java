package jp.cobolinsight.frontend.jcl.instream;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;

/**
 * The SYSIN cards of an {@code IEBCOPY} step. A COPY or COPYMOD card names the library it reads
 * and the one it writes by their DD names; a step that names neither copies SYSUT1 to SYSUT2,
 * which {@link UtilityDdRoles} states.
 */
final class Iebcopy {

    static final String PROGRAM = "IEBCOPY";

    static final String CONTROL_DD = "SYSIN";

    private static final Pattern INDD =
            Pattern.compile("\\bINDD=([A-Z0-9@#$]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTDD =
            Pattern.compile("\\bOUTDD=([A-Z0-9@#$]+)", Pattern.CASE_INSENSITIVE);

    private Iebcopy() {
    }

    /** Reads one SYSIN stream into the roles its COPY cards give the step's DD statements. */
    static void read(List<String> lines, Map<String, DatasetAccess> ddRoles) {
        for (String card : Cards.comma(lines)) {
            put(ddRoles, INDD.matcher(card), DatasetAccess.READ);
            put(ddRoles, OUTDD.matcher(card), DatasetAccess.WRITE);
        }
    }

    private static void put(Map<String, DatasetAccess> ddRoles, Matcher matcher,
            DatasetAccess access) {
        while (matcher.find()) {
            ddRoles.put(matcher.group(1).toUpperCase(Locale.ROOT), access);
        }
    }
}
