package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourceRange;

/** 手続き部の文。順次は文のリスト、分岐・反復は {@link CompoundStatement}、GO TO は専用型で表す。 */
public sealed interface Statement permits SimpleStatement, CompoundStatement, GoToStatement {

    SourceRange range();
}
