package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.jcl.JclUtilityFacts.ResourceDefinition;

/**
 * The SYSIN commands of {@code DFHCSDUP}, the batch utility that maintains the CICS system
 * definition file. Only DEFINE is read: it is the command that says which program a transaction
 * starts, which is what joins a transaction ID to the code behind it.
 *
 * <p>A command runs over as many lines as it needs and ends where the next one begins, so a line is
 * taken as a continuation unless its first word is a command of the utility. A line with
 * {@code *} in column 1 is a comment.
 */
final class Csdup {

    static final String PROGRAM = "DFHCSDUP";

    static final String CONTROL_DD = "SYSIN";

    private static final Set<String> COMMANDS = Set.of("ADD", "ALTER", "APPEND", "COPY", "DEFINE",
            "DELETE", "EXTRACT", "INITIALIZE", "LIST", "MIGRATE", "PROCESS", "REMOVE", "SCAN",
            "SERVICE", "UPGRADE", "USERDEFINE", "VERIFY");

    private static final Pattern KEYWORD = Pattern.compile(
            "([A-Z][A-Z0-9]*)\\s*\\(\\s*('[^']*'|[^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final int STATEMENT_AREA_END = 72;

    private Csdup() {
    }

    static void read(List<String> lines, List<ResourceDefinition> definitions) {
        for (String command : commands(lines)) {
            String upper = command.toUpperCase(Locale.ROOT);
            if (!upper.startsWith("DEFINE ") && !upper.startsWith("DEF ")) {
                continue;
            }
            Matcher keyword = KEYWORD.matcher(command.substring(command.indexOf(' ')));
            String type = null;
            String name = null;
            Map<String, String> attributes = new LinkedHashMap<>();
            while (keyword.find()) {
                String key = keyword.group(1).toUpperCase(Locale.ROOT);
                String value = Cards.unquote(keyword.group(2)).strip();
                if (type == null) {
                    // The first keyword of a DEFINE is the resource type, its value the name.
                    type = key;
                    name = value.toUpperCase(Locale.ROOT);
                } else {
                    attributes.putIfAbsent(key, value);
                }
            }
            if (type != null && name != null && !name.isEmpty()) {
                definitions.add(new ResourceDefinition(type, name, attributes));
            }
        }
    }

    private static List<String> commands(List<String> lines) {
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : lines) {
            String line = raw.length() > STATEMENT_AREA_END
                    ? raw.substring(0, STATEMENT_AREA_END) : raw;
            if (line.isBlank() || line.startsWith("*")) {
                continue;
            }
            String body = line.strip();
            int blank = body.indexOf(' ');
            String first = (blank < 0 ? body : body.substring(0, blank)).toUpperCase(Locale.ROOT);
            if (COMMANDS.contains(first) || first.equals("DEF")) {
                if (current.length() > 0) {
                    commands.add(current.toString());
                }
                current = new StringBuilder(body);
            } else if (current.length() > 0) {
                current.append(' ').append(body);
            }
        }
        if (current.length() > 0) {
            commands.add(current.toString());
        }
        return commands;
    }
}
