package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.source.DecodedSource;

/**
 * 文字コード判別・復号の契約。encoding モジュールが実装し、ServiceLoader で発見する。
 */
public interface CharsetProvider {

    /** 文字コードを自動判別して復号する。判別結果と確信度は戻り値の EncodingInfo が保持する。 */
    DecodedSource decode(String path, byte[] bytes);

    /** 利用者が手動指定した文字コードで復号する。戻り値の EncodingInfo は manualOverride=true。 */
    DecodedSource decode(String path, byte[] bytes, String charsetName);
}
