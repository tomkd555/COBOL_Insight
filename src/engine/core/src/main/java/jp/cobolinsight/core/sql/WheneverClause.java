package jp.cobolinsight.core.sql;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an {@code EXEC SQL WHENEVER} statement. The precompiler turns it into a branch after
 * every later SQL statement, so its GO TO or PERFORM target is a procedure that runs, and a
 * program that relies on it has handled the negative SQLCODEs of those statements. WHENEVER is
 * a precompiler directive: for each condition the last WHENEVER before a statement in source
 * order is the one in effect, and {@code CONTINUE} cancels an earlier branch.
 */
public final class WheneverClause {

    private static final Pattern CLAUSE = Pattern.compile(
            "WHENEVER (SQLERROR|SQLWARNING|NOT FOUND) "
                    + "(?:(CONTINUE)|(?:GO ?TO|PERFORM) ([\\p{L}\\p{N}][\\p{L}\\p{N}-]*))");

    private WheneverClause() {
    }

    /** The condition and what a WHENEVER does about it; empty for another SQL statement. */
    public static Optional<Clause> clauseOf(String execSqlText) {
        String normalized = execSqlText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        Matcher matcher = CLAUSE.matcher(normalized);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new Clause(matcher.group(1), Optional.ofNullable(matcher.group(3))));
    }

    /** The condition and target of a WHENEVER that branches; empty for CONTINUE or another SQL statement. */
    public static Optional<Branch> branchOf(String execSqlText) {
        return clauseOf(execSqlText).filter(Clause::branches)
                .map(clause -> new Branch(clause.condition(), clause.target().orElseThrow()));
    }

    /** The condition (SQLERROR, SQLWARNING, NOT FOUND) and the procedure it branches to; no target for CONTINUE. */
    public record Clause(String condition, Optional<String> target) {

        public boolean branches() {
            return target.isPresent();
        }
    }

    /** The condition (SQLERROR, SQLWARNING, NOT FOUND) and the procedure the branch goes to. */
    public record Branch(String condition, String target) {

        public boolean onError() {
            return "SQLERROR".equals(condition);
        }
    }
}
