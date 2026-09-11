package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;

/**
 * The DD names a utility always uses the same way, and what the DISP of a DD says when the utility
 * says nothing. One place holds the table, so a per-utility class need only add what its own
 * control cards state.
 *
 * <p>A role a card stated is never replaced here: the cards know which of a step's DD statements
 * the run actually reads, and the table only fills in what they left unsaid.
 *
 * <p>The fallback reads a DD three ways. A DD with in-stream data under it is read by the step,
 * whatever else the DD says; a DD that goes to the printer is written; and otherwise the DISP
 * status decides. {@code IEFBR14} is the one program the fallback is not applied to: it runs no
 * code at all, so what its DD statements say is exactly what {@link Iefbr14} reads off their
 * disposition, and a fallback would have it reading control cards it never opens.
 */
final class UtilityDdRoles {

    /** One utility's DD names, as patterns over the whole name. */
    private record Roles(Pattern read, Pattern write) {
    }

    private static final Map<String, Roles> BY_PROGRAM = Map.ofEntries(
            Map.entry("IEBGENER", new Roles(Pattern.compile("SYSUT1"), Pattern.compile("SYSUT2"))),
            Map.entry("IEBUPDTE", new Roles(Pattern.compile("SYSUT1"), Pattern.compile("SYSUT2"))),
            Map.entry("IEBPTPCH", new Roles(Pattern.compile("SYSUT1"), Pattern.compile("SYSUT2"))),
            Map.entry("IEBCOPY",
                    new Roles(Pattern.compile("SYSUT1|INDD"), Pattern.compile("SYSUT2|OUTDD"))),
            Map.entry("SORT", sort()),
            Map.entry("ICEMAN", sort()),
            Map.entry("SYNCSORT", sort()),
            Map.entry("DFSORT", sort()));

    /** The DD names every sort product reads and writes without being told to. */
    private static Roles sort() {
        return new Roles(Pattern.compile("SORTIN|SORTIN\\d\\d|SORTJNF1|SORTJNF2"),
                Pattern.compile("SORTOUT|SORTOF\\d\\d"));
    }

    private UtilityDdRoles() {
    }

    /** Adds the roles the utility's own table and the DD statements' DISP state. */
    static void apply(String program, JclStep step, Map<String, DatasetAccess> roles) {
        if (Iefbr14.PROGRAM.equals(program)) {
            return;
        }
        Roles named = BY_PROGRAM.get(program);
        for (JclDdStatement dd : step.ddStatements()) {
            String name = dd.ddName().toUpperCase(Locale.ROOT);
            if (roles.containsKey(name) || dd.dummy()) {
                // A DUMMY DD names no data set at all, so the step uses nothing through it.
                continue;
            }
            if (named != null && named.read().matcher(name).matches()) {
                roles.put(name, DatasetAccess.READ);
            } else if (named != null && named.write().matcher(name).matches()) {
                roles.put(name, DatasetAccess.WRITE);
            } else {
                fallback(dd).ifPresent(access -> roles.put(name, access));
            }
        }
    }

    /**
     * What a DD's own parameters say when no card names it: its DISP status, or WRITE for a DD
     * that goes to the printer. A DD with neither says nothing, and takes no entry rather than an
     * entry the source does not state.
     */
    private static Optional<DatasetAccess> fallback(JclDdStatement dd) {
        if (dd.sysout().isPresent()) {
            return Optional.of(DatasetAccess.WRITE);
        }
        if (!dd.inStreamData().isEmpty()) {
            // The step reads the cards written under the DD, whatever else the DD says.
            return Optional.of(DatasetAccess.READ);
        }
        return dd.disposition().map(disposition -> switch (disposition.status()
                .toUpperCase(Locale.ROOT)) {
            case "NEW", "MOD" -> DatasetAccess.WRITE;
            case "OLD" -> DatasetAccess.UPDATE;
            case "SHR" -> DatasetAccess.READ;
            default -> DatasetAccess.UNKNOWN;
        });
    }

    /**
     * The cards every one of these programs reads its control statements from: the in-stream data
     * of the named DD and of every entry concatenated under it, in the order the program reads
     * them, because a concatenation is one stream to the program.
     *
     * <p>A control DD that names a data set instead of opening a stream yields no cards at all.
     * Nothing here reads the site's files, so a step whose control statements are filed says
     * nothing about itself beyond what its own DD statements say.
     */
    static List<String> cards(JclStep step, String ddName) {
        List<String> cards = new ArrayList<>();
        for (JclDdStatement dd : step.ddStatements()) {
            if (dd.ddName().equalsIgnoreCase(ddName)) {
                cards.addAll(dd.inStreamData());
            }
        }
        return cards;
    }
}
