package jp.cobolinsight.core.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /** The indicator area, column 7, of a line that starts at column 1. */
    private static final int INDICATOR_INDEX = 6;
    private static final String NAME = "[\\p{L}\\p{N}][\\p{L}\\p{N}-]*";
    /** {@code OF section} or {@code IN section} after a paragraph name. */
    private static final String QUALIFIER = "(?:\\s+(?:OF|IN)\\s+(" + NAME + "))?";
    private static final Pattern GO_TO = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])GO\\s+TO\\s+(" + NAME + ")" + QUALIFIER);
    /** One more word after a GO TO target, with the qualifier it may carry. */
    private static final Pattern NEXT_TARGET = Pattern.compile(
            "(?i)[\\s,]+(" + NAME + ")" + QUALIFIER);
    /** The words that open the phrase after a GO TO's last target, or close the statement. */
    private static final Set<String> PHRASE_WORDS = Set.of("NOT", "AT", "END", "INVALID", "KEY",
            "ON", "SIZE", "ERROR", "EXCEPTION", "OVERFLOW", "EOP", "END-OF-PAGE", "GO", "TO",
            "ELSE", "WHEN", "THEN");
    private static final Pattern ALTER = Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])ALTER(?![\\p{L}\\p{N}-])");
    /** One {@code p TO [PROCEED TO] target} pair of an ALTER; the statement may list several. */
    private static final Pattern ALTER_PAIR = Pattern.compile(
            "(?i)" + NAME + "\\s+TO\\s+(?:PROCEED\\s+TO\\s+)?(" + NAME + ")" + QUALIFIER);
    private static final Pattern HANDLE = Pattern.compile(
            "(?i)EXEC\\s+CICS\\s+HANDLE\\s+(CONDITION|AID|ABEND)(?![\\p{L}\\p{N}-])");
    private static final Pattern LABEL_OPERAND = Pattern.compile(
            "\\(\\s*(" + NAME + ")\\s*\\)");
    private static final Pattern ABEND_LABEL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])LABEL\\s*\\(\\s*(" + NAME + ")\\s*\\)");

    /**
     * One branch target read off a statement's text: the paragraph name, the section that
     * qualifies it where the text writes one, and where the branch stands in the text, as a
     * 0-based line within the text and a 0-based offset within that line. For a GO TO that is
     * where {@code GO} stands; for an ALTER pair or a HANDLE label it is where the name stands.
     */
    public record Branch(String target, Optional<String> section, int line, int offset) {
    }

    private NestedBranches() {
    }

    /**
     * Every branch the control-flow graph follows out of a statement's text: its GO TOs, then
     * the targets its ALTER makes other GO TOs branch to, then the labels of its EXEC CICS
     * HANDLE, each list in the order written.
     */
    public static List<Branch> allBranches(String statementText) {
        List<Branch> branches = new ArrayList<>(goToBranches(statementText));
        branches.addAll(alterBranches(statementText));
        branches.addAll(handleBranches(statementText));
        return branches;
    }

    /**
     * The {@code GO TO} branches inside the statement's text, in the order they are written. A
     * {@code GO TO A B C DEPENDING ON X} gives one branch per target, all placed where its
     * {@code GO} stands.
     */
    public static List<Branch> goToBranches(String statementText) {
        String text = masked(statementText);
        List<Branch> branches = new ArrayList<>();
        Matcher matcher = GO_TO.matcher(text);
        while (matcher.find()) {
            branches.add(branch(text, matcher.group(1), matcher.group(2), matcher.start()));
            branches.addAll(dependingTargets(text, matcher.end(), matcher.start()));
        }
        return branches;
    }

    /**
     * The targets after the first of a {@code GO TO ... DEPENDING ON}, read from {@code from}: the
     * names that follow, up to the word DEPENDING. Without that word the GO TO has its first
     * target alone, and the words after it belong to the statement's next phrase.
     */
    private static List<Branch> dependingTargets(String text, int from, int at) {
        List<Branch> targets = new ArrayList<>();
        Matcher next = NEXT_TARGET.matcher(text);
        int position = from;
        while (next.region(position, text.length()).lookingAt()) {
            String word = next.group(1).toUpperCase(Locale.ROOT);
            if (word.equals("DEPENDING")) {
                return targets;
            }
            if (PHRASE_WORDS.contains(word) || word.startsWith("END-")) {
                break;
            }
            targets.add(branch(text, next.group(1), next.group(2), at));
            position = next.end();
        }
        return List.of();
    }

    /** The {@code PROCEED TO} targets of an {@code ALTER}, one per pair, in the order written. */
    public static List<Branch> alterBranches(String statementText) {
        String text = masked(statementText);
        List<Branch> branches = new ArrayList<>();
        Matcher alter = ALTER.matcher(text);
        if (!alter.find()) {
            return branches;
        }
        Matcher pair = ALTER_PAIR.matcher(text);
        pair.region(alter.end(), text.length());
        while (pair.find()) {
            branches.add(branch(text, pair.group(1), pair.group(2), pair.start(1)));
        }
        return branches;
    }

    /**
     * The labels of an {@code EXEC CICS HANDLE}, in the order written: CICS branches to them the
     * way a GO TO would. Every parenthesised operand of a HANDLE CONDITION or HANDLE AID is a
     * label; of a HANDLE ABEND, the operand of {@code LABEL(...)} alone, since its
     * {@code PROGRAM(...)} names a program.
     */
    public static List<Branch> handleBranches(String statementText) {
        String text = masked(statementText);
        List<Branch> branches = new ArrayList<>();
        Matcher handle = HANDLE.matcher(text);
        if (!handle.find()) {
            return branches;
        }
        Pattern operand = handle.group(1).equalsIgnoreCase("ABEND") ? ABEND_LABEL : LABEL_OPERAND;
        Matcher label = operand.matcher(text);
        label.region(handle.end(), text.length());
        while (label.find()) {
            branches.add(branch(text, label.group(1), null, label.start(1)));
        }
        return branches;
    }

    /**
     * The statement text without the comment lines its span contains. The first line starts
     * where the statement does and is never a comment.
     */
    public static String withoutCommentLines(String statementText) {
        if (statementText == null) {
            return "";
        }
        String[] lines = statementText.split("\n", -1);
        StringBuilder out = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(isCommentLine(lines[i]) ? "" : lines[i]);
        }
        return out.toString();
    }

    /**
     * Whether a line of a statement's text after its first is a comment line: one with
     * {@code *} or {@code /} in the indicator area. Such a line starts at column 1, so the
     * indicator area, column 7, is {@link #INDICATOR_INDEX}.
     */
    private static boolean isCommentLine(String line) {
        return line.length() > INDICATOR_INDEX
                && (line.charAt(INDICATOR_INDEX) == '*' || line.charAt(INDICATOR_INDEX) == '/');
    }

    private static boolean isContinuationLine(String line) {
        return line.length() > INDICATOR_INDEX && line.charAt(INDICATOR_INDEX) == '-';
    }

    private static Branch branch(String text, String target, String section, int at) {
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        int line = 0;
        for (int i = 0; i < lineStart; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return new Branch(target, Optional.ofNullable(section), line, at - lineStart);
    }

    /**
     * The statement text with its comment lines and its literals blanked. Every other character,
     * line breaks included, keeps its place, so an offset into the result is an offset into the
     * statement text.
     *
     * <p>Literals are read line by line. A literal still open at the end of a line goes on in the
     * next line when that one is a continuation line ({@code -} in the indicator area), from the
     * quote that opens its continued part; a line of any other kind closes it. A doubled quote
     * inside a literal is part of it.
     */
    private static String masked(String statementText) {
        if (statementText == null) {
            return "";
        }
        String[] lines = statementText.split("\n", -1);
        StringBuilder out = new StringBuilder(statementText.length());
        char open = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                out.append('\n');
            }
            if (i > 0 && isCommentLine(line)) {
                out.append(" ".repeat(line.length()));
                continue;
            }
            StringBuilder masked = new StringBuilder(line);
            int start = 0;
            if (open != 0) {
                int resumed = i > 0 && isContinuationLine(line)
                        ? line.indexOf(open, INDICATOR_INDEX + 1) : -1;
                if (resumed < 0) {
                    open = 0;
                } else {
                    blank(masked, 0, resumed + 1);
                    start = resumed + 1;
                }
            }
            for (int j = start; j < line.length(); j++) {
                char c = line.charAt(j);
                if (open == 0) {
                    if (c == '\'' || c == '"') {
                        open = c;
                        masked.setCharAt(j, ' ');
                    }
                    continue;
                }
                masked.setCharAt(j, ' ');
                if (c == open) {
                    if (j + 1 < line.length() && line.charAt(j + 1) == open) {
                        masked.setCharAt(++j, ' ');
                    } else {
                        open = 0;
                    }
                }
            }
            out.append(masked);
        }
        return out.toString();
    }

    private static void blank(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            text.setCharAt(i, ' ');
        }
    }
}
