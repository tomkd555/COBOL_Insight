package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Command.REPORT} and {@code Command.SCAN} were removed from {@link Command}; a
 * {@code rules.json} written by an earlier build still offers them, and {@link CustomRules#of}
 * must keep reading it rather than dropping the whole custom rule.
 */
class CustomRulesTest {

    @ParameterizedTest
    @MethodSource("commandsAliasedToLint")
    void reportAndScanAreReadAsLint(List<String> commands) {
        Rule rule = CustomRules.of(ruleObject(commands));

        assertEquals(Set.of(Command.LINT), rule.meta().commands());
    }

    private static Stream<List<String>> commandsAliasedToLint() {
        return Stream.of(List.of("LINT", "REPORT"), List.of("SCAN"));
    }

    @Test
    void aGenuinelyUnknownCommandIsStillRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CustomRules.of(ruleObject(List.of("BOGUS"))));

        assertTrue(e.getMessage().contains("LINT・SQL_LINT・FIX"), e.getMessage());
    }

    private static Map<String, Object> ruleObject(List<String> commands) {
        return Map.of(
                "id", "U001",
                "name", "常に一致する検査",
                "message", "PROGRAM-ID 行に一致した",
                "commands", commands,
                "match", Map.of("kind", "line", "regex", "PROGRAM-ID"));
    }
}
