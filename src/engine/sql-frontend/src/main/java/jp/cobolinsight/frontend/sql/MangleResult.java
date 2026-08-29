package jp.cobolinsight.frontend.sql;

/** ホスト変数マングリングの結果。成功か「解析対象外」かの2値。 */
public sealed interface MangleResult {

    /** マングリング成功。 */
    record Mangled(MangledSql sql) implements MangleResult {
    }

    /** マングリング不能。対象SQLは解析対象外として報告する。 */
    record NotAnalyzable(String reason) implements MangleResult {
    }
}
