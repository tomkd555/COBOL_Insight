package jp.cobolinsight.rules;

import java.util.Set;

/**
 * Rule IDs that existed in V1 and no longer exist. A {@code rules.json} still naming one of these
 * gets a message saying the rule was removed, rather than the generic "unknown rule" message: a
 * configuration file outlives the rule it configures, and the two cases call for different action.
 */
public final class RemovedRules {

    private static final Set<String> IDS = Set.of();

    private RemovedRules() {
    }

    public static boolean contains(String ruleId) {
        return IDS.contains(ruleId);
    }
}
