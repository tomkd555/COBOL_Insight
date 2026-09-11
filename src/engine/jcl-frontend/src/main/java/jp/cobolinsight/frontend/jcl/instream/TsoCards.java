package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.BindRequest;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetUse;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;

/**
 * The SYSTSIN cards of a TSO batch step: what {@code IKJEFT01} and its two authorised twins are
 * told to do.
 *
 * <p>Inside {@code DSN SYSTEM(x)} the cards are the Db2 command processor's own — RUN, BIND and
 * REBIND, which are read only there and no longer once END has closed the DSN command. Outside it
 * as well as inside it the cards are TSO commands: CALL runs a load module, ALLOCATE and DELETE
 * name data sets, and FREE and LISTCAT change nothing worth recording.
 */
final class TsoCards {

    /** The launchers that read their work from SYSTSIN. */
    private static final Set<String> LAUNCHERS = Set.of("IKJEFT01", "IKJEFT1A", "IKJEFT1B");

    static final String CONTROL_DD = "SYSTSIN";

    private static final Pattern RUN = Pattern.compile(
            "^RUN\\s+PROG(?:RAM)?\\s*\\(([^)]+)\\)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BIND = Pattern.compile(
            "^(RE)?BIND\\s+(PLAN|PACKAGE)\\s*\\(([^)]+)\\)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile(
            "^CALL\\s+'([^']*)'\\s*(?:'([^']*)')?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOCATE = Pattern.compile(
            "^ALLOC(?:ATE)?\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE =
            Pattern.compile("^DELETE\\b(.*)$", Pattern.CASE_INSENSITIVE);

    /** The command that opens the Db2 command processor, and the one that closes it. */
    private static final Pattern DSN = Pattern.compile("^DSN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile("^END\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEYWORD = Pattern.compile(
            "([A-Z][A-Z0-9]*)\\s*\\(\\s*('[^']*'|[^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** {@code 'CCP.PROD.LOADLIB(CCP006)'}: the library a TSO CALL names, and the member in it. */
    private static final Pattern LIBRARY_MEMBER = Pattern.compile("^(.*)\\(([^)]+)\\)$");

    /** The keywords a BIND card carries that the model keeps a field of its own for. */
    private static final Set<String> BIND_FIELDS = Set.of("PLAN", "PACKAGE", "MEMBER");

    private TsoCards() {
    }

    static boolean isLauncher(String program) {
        return LAUNCHERS.contains(program);
    }

    /** Reads one SYSTSIN stream into the facts its cards state. */
    static void read(List<String> lines, List<ProgramRun> runs, List<BindRequest> binds,
            List<DatasetUse> datasetUses, Map<String, DatasetAccess> ddRoles) {
        boolean underDsn = false;
        for (String card : Cards.continued(lines)) {
            if (DSN.matcher(card).find()) {
                underDsn = true;
                continue;
            }
            if (END.matcher(card).find()) {
                underDsn = false;
                continue;
            }
            Matcher run = RUN.matcher(card);
            if (underDsn && run.matches()) {
                Map<String, String> keywords = keywords(run.group(2));
                runs.add(new ProgramRun(run.group(1).strip(),
                        Optional.ofNullable(keywords.get("PLAN")),
                        Optional.ofNullable(keywords.containsKey("PARMS")
                                ? keywords.get("PARMS") : keywords.get("PARM")),
                        Optional.ofNullable(keywords.get("LIB"))));
                continue;
            }
            Matcher bind = BIND.matcher(card);
            if (underDsn && bind.matches()) {
                Map<String, String> keywords = keywords(bind.group(4));
                Map<String, String> options = new LinkedHashMap<>(keywords);
                options.keySet().removeIf(BIND_FIELDS::contains);
                String kind = (bind.group(1) == null ? "" : "REBIND-")
                        + bind.group(2).toUpperCase(Locale.ROOT);
                binds.add(new BindRequest(kind, bind.group(3).strip(),
                        names(keywords.get("MEMBER")), options));
                continue;
            }
            Matcher call = CALL.matcher(card);
            if (call.find()) {
                Matcher named = LIBRARY_MEMBER.matcher(call.group(1).strip());
                runs.add(named.matches()
                        ? new ProgramRun(named.group(2).strip(), Optional.empty(),
                                Optional.ofNullable(call.group(2)), Optional.of(named.group(1)))
                        : new ProgramRun(call.group(1).strip(), Optional.empty(),
                                Optional.ofNullable(call.group(2)), Optional.empty()));
                continue;
            }
            Matcher allocate = ALLOCATE.matcher(card);
            if (allocate.matches()) {
                allocate(allocate.group(1), datasetUses, ddRoles);
                continue;
            }
            Matcher delete = DELETE.matcher(card);
            if (delete.matches()) {
                for (String name : Cards.operandNames(delete.group(1))) {
                    datasetUses.add(new DatasetUse(name, DatasetAccess.DELETE));
                }
            }
        }
    }

    /** {@code ALLOCATE DA(dsn) FI(ddname) SHR}: one data set, and the DD it is allocated to. */
    private static void allocate(String operands, List<DatasetUse> datasetUses,
            Map<String, DatasetAccess> ddRoles) {
        Map<String, String> keywords = keywords(operands);
        String dataset = null;
        for (String keyword : List.of("DA", "DATASET", "DSNAME")) {
            dataset = dataset != null ? dataset : keywords.get(keyword);
        }
        DatasetAccess access = access(operands);
        if (dataset != null && !dataset.isBlank()) {
            datasetUses.add(new DatasetUse(Cards.unquote(dataset), access));
        }
        for (String keyword : List.of("DD", "FI", "FILE", "DDNAME")) {
            String ddName = keywords.get(keyword);
            if (ddName != null && !ddName.isBlank()) {
                ddRoles.put(Cards.unquote(ddName).toUpperCase(Locale.ROOT), access);
                return;
            }
        }
    }

    /** The status word an ALLOCATE carries, which says how the step means to use the data set. */
    private static DatasetAccess access(String operands) {
        String text = " " + operands.toUpperCase(Locale.ROOT) + " ";
        if (text.contains(" NEW ")) {
            return DatasetAccess.CREATE;
        }
        if (text.contains(" OLD ")) {
            return DatasetAccess.UPDATE;
        }
        if (text.contains(" MOD ")) {
            return DatasetAccess.WRITE;
        }
        return text.contains(" SHR ") ? DatasetAccess.READ : DatasetAccess.UNKNOWN;
    }

    /** The {@code KEYWORD(value)} pairs of a card, in the order they were written. */
    private static Map<String, String> keywords(String operands) {
        Map<String, String> keywords = new LinkedHashMap<>();
        if (operands == null) {
            return keywords;
        }
        Matcher matcher = KEYWORD.matcher(operands);
        while (matcher.find()) {
            keywords.putIfAbsent(matcher.group(1).toUpperCase(Locale.ROOT),
                    Cards.unquote(matcher.group(2)));
        }
        return keywords;
    }

    /** A comma-separated list of names, as MEMBER() and PKLIST() are written. */
    private static List<String> names(String value) {
        List<String> names = new ArrayList<>();
        if (value == null) {
            return names;
        }
        for (String name : value.split(",")) {
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        }
        return names;
    }

}
