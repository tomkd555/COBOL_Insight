package jp.cobolinsight.frontend.jcl.instream;

import java.util.Locale;
import java.util.Map;

import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;

/**
 * An {@code IEFBR14} step reads and writes nothing: the program returns at once. What the step
 * does is what its DD statements' DISP asks the system to do — allocate a data set, or delete one —
 * so the role of each DD is read off the disposition alone.
 *
 * <p>A status of NEW or MOD allocates the data set, whatever becomes of it afterwards, so the DD
 * creates it; PASS and a disposition left out allocate it just as CATLG does. A normal disposition
 * of DELETE is the one fact that outranks this, because a step written to delete a data set is
 * written for that and nothing else.
 */
final class Iefbr14 {

    static final String PROGRAM = "IEFBR14";

    private Iefbr14() {
    }

    /** Reads the step's DD statements into what their disposition asks for. */
    static void read(JclStep step, Map<String, DatasetAccess> ddRoles) {
        for (JclDdStatement dd : step.ddStatements()) {
            if (dd.dummy() || dd.disposition().isEmpty()) {
                continue;
            }
            String status = dd.disposition().orElseThrow().status().toUpperCase(Locale.ROOT);
            String normal = dd.disposition().orElseThrow().normal().orElse("")
                    .toUpperCase(Locale.ROOT);
            String name = dd.ddName().toUpperCase(Locale.ROOT);
            if (normal.equals("DELETE")) {
                ddRoles.put(name, DatasetAccess.DELETE);
            } else if (status.equals("NEW") || status.equals("MOD")) {
                ddRoles.put(name, DatasetAccess.CREATE);
            }
        }
    }
}
