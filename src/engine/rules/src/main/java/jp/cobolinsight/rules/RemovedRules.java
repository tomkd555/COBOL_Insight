package jp.cobolinsight.rules;

import java.util.Set;

/**
 * Rule IDs that existed in V1 and no longer exist. A {@code rules.json} still naming one of these
 * gets a message saying the rule was removed, rather than the generic "unknown rule" message: a
 * configuration file outlives the rule it configures, and the two cases call for different action.
 */
public final class RemovedRules {

    /**
     * S003 was folded into S002: a function on a column is one way of being non-SARGable, the two
     * rules gave the same advice, and a function on the left of a comparison reported the same
     * predicate twice. S005 and S006 were dropped: they fired on every SELECT and on every cursor
     * declaration respectively, which over samples and corpus came to four and two findings for no
     * defect at all. See {@code corpus/rule-hits.md}.
     */
    private static final Set<String> IDS = Set.of("S003", "S005", "S006");

    private RemovedRules() {
    }

    public static boolean contains(String ruleId) {
        return IDS.contains(ruleId);
    }
}
