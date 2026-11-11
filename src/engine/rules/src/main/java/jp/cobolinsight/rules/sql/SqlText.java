package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.Locale;

/**
 * The text of a statement as a keyword pattern can read it: upper case, layout collapsed to single
 * blanks, and every string literal emptied, so a word inside a literal is never taken for a
 * keyword. The mangled text is used where the grammar produced one, because a host variable there
 * is one token instead of a name with hyphens in it.
 */
final class SqlText {

    private SqlText() {
    }

    static String of(SqlStatementModel statement) {
        String text = statement.mangledText().isBlank()
                ? statement.originalText() : statement.mangledText();
        return text.replaceAll("'[^']*'", "''").replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT).strip();
    }
}
