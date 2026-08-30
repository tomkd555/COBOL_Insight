package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

/** A statement in the procedure division. Sequence is a list of statements; branch/loop is represented by {@link CompoundStatement}, and GO TO by a dedicated type. */
public sealed interface Statement permits SimpleStatement, CompoundStatement, GoToStatement {

    SourceRange range();
}
