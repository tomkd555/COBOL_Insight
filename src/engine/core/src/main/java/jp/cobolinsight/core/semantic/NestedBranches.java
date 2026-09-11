package jp.cobolinsight.core.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branches written inside another statement, which the semantic model keeps as text: the
 * {@code GO TO} of a {@code READ ... AT END GO TO x}, an {@code INVALID KEY GO TO x}, an
 * {@code ON SIZE ERROR GO TO x}, and the {@code PROCEED TO} targets of an {@code ALTER}. The
 * control-flow graph and the reachability rule read them from here so that both agree. The
 * statement text is the source span of the statement with columns 1-6 and 73+ blanked; a comment
 * line inside that span is dropped before matching, so a commented-out branch is not a branch.
 */
public final class NestedBranches {

    private static final Pattern LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern GO_TO = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])GO\\s+TO\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)");
    private static final Pattern ALTER = Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])ALTER(?![\\p{L}\\p{N}-])");
    /** One {@code p TO [PROCEED TO] target} pair of an ALTER; the statement may list several. */
    private static final Pattern ALTER_PAIR = Pattern.compile(
            "(?i)[\\p{L}\\p{N}][\\p{L}\\p{N}-]*\\s+TO\\s+(?:PROCEED\\s+TO\\s+)?"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)");
    private static final Pattern HANDLE = Pattern.compile(
            "(?i)EXEC\\s+CICS\\s+HANDLE\\s+(?:CONDITION|AID|ABEND)(?![\\p{L}\\p{N}-])");
    private static final Pattern LABEL_OPERAND = Pattern.compile(
            "\\(\\s*([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)\\s*\\)");

    private NestedBranches() {
    }

    /** Paragraph names that a {@code GO TO} inside the statement's text branches to. */
    public static List<String> goToTargets(String statementText) {
        List<String> targets = new ArrayList<>();
        Matcher matcher = GO_TO.matcher(withoutLiterals(statementText));
        while (matcher.find()) {
            targets.add(matcher.group(1));
        }
        return targets;
    }

    /** The paragraphs an {@code ALTER ... TO PROCEED TO} makes its GO TOs branch to, one per pair. */
    public static List<String> alterTargets(String statementText) {
        List<String> targets = new ArrayList<>();
        String text = withoutLiterals(statementText);
        Matcher alter = ALTER.matcher(text);
        if (!alter.find()) {
            return targets;
        }
        Matcher pair = ALTER_PAIR.matcher(text.substring(alter.end()));
        while (pair.find()) {
            targets.add(pair.group(1));
        }
        return targets;
    }

    /**
     * The paragraphs an {@code EXEC CICS HANDLE CONDITION}, {@code HANDLE AID} or
     * {@code HANDLE ABEND} names as labels: CICS branches to them the way a GO TO would. A
     * {@code HANDLE ABEND PROGRAM(x)} names a program, which no paragraph matches.
     */
    public static List<String> handleTargets(String statementText) {
        List<String> targets = new ArrayList<>();
        String text = withoutLiterals(statementText);
        Matcher handle = HANDLE.matcher(text);
        if (!handle.find()) {
            return targets;
        }
        Matcher label = LABEL_OPERAND.matcher(text.substring(handle.end()));
        while (label.find()) {
            targets.add(label.group(1));
        }
        return targets;
    }

    /**
     * The statement text without the comment lines its span contains. A line after the first
     * starts at column 1, so column 7 is index 6; the first line starts where the statement
     * does and is never a comment.
     */
    public static String withoutCommentLines(String statementText) {
        if (statementText == null) {
            return "";
        }
        String[] lines = statementText.split("\n", -1);
        StringBuilder out = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            boolean comment = line.length() > 6 && (line.charAt(6) == '*' || line.charAt(6) == '/');
            out.append('\n').append(comment ? "" : line);
        }
        return out.toString();
    }

    private static String withoutLiterals(String text) {
        return LITERAL.matcher(withoutCommentLines(text)).replaceAll(" ");
    }
}
