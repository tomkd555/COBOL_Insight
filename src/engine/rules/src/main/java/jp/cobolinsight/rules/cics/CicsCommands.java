package jp.cobolinsight.rules.cics;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The EXEC CICS commands of a program read as command and operands. The semantic model keeps the
 * operands only of the commands the call graph needs, so the rules about files, storage and
 * browses read the command text here.
 */
final class CicsCommands {

    /** One command: its verb words up to the first operand with a value, and the block it is. */
    record Command(String body, EmbeddedBlock block) {

        boolean is(String verb) {
            return body.equals(verb) || body.startsWith(verb + " ");
        }

        boolean has(String option) {
            return Pattern.compile("\\b" + Pattern.quote(option) + "\\b").matcher(body).find();
        }

        /** The value of {@code OPERAND(value)}, apostrophes kept, or empty. */
        Optional<String> operand(String... names) {
            for (String name : names) {
                Matcher matcher = Pattern.compile("\\b" + name + "\\s*\\(\\s*([^)]*?)\\s*\\)")
                        .matcher(body);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
            return Optional.empty();
        }
    }

    private CicsCommands() {
    }

    static List<Command> of(CobolSemanticModel model) {
        List<Command> commands = new ArrayList<>();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind().isCics()) {
                String body = CfgSupport.cicsBody(block.text())
                        .replaceAll("\\s*END-EXEC\\.?\\s*$", "").strip();
                commands.add(new Command(body, block));
            }
        }
        return commands;
    }

    static boolean isCicsProgram(CobolSemanticModel model) {
        return model.embeddedBlocks().stream().anyMatch(block -> block.kind().isCics());
    }

    static Finding finding(RuleMeta meta, Command command, String message) {
        return Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message,
                CfgSupport.at(command.block().range()));
    }
}
