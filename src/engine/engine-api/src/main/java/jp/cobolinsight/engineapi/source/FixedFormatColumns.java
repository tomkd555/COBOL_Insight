package jp.cobolinsight.engineapi.source;

/**
 * COBOL 固定形式の桁境界の正典。桁番号は1起点で、参照する側が0起点を要するときは
 * {@code -1} して導出する。起点の変換を利用側の1行に閉じ込め、同じ物理量を複数の起点で
 * 持たないためである。
 *
 * <p>ここの値は COBOL の言語規格が定める固定形式の領域であり、本ツールが選んだ数値ではない。
 */
public final class FixedFormatColumns {

    /** 一連番号領域の開始桁。 */
    public static final int SEQUENCE_START = 1;
    /** 一連番号領域の終了桁。 */
    public static final int SEQUENCE_END = 6;
    /** 標識領域の桁。注記(*)・行送り(/)・継続(-)を置く。 */
    public static final int INDICATOR_COLUMN = 7;
    /** A領域の開始桁。本文はここから始まる。 */
    public static final int AREA_A_START = 8;
    /** A領域の終了桁。 */
    public static final int AREA_A_END = 11;
    /** B領域の開始桁。 */
    public static final int AREA_B_START = 12;
    /** 本文を収められる最終桁。 */
    public static final int CONTENT_END = 72;
    /** 識別領域の開始桁。ここから先は本文ではない。 */
    public static final int IDENTIFICATION_START = 73;

    private FixedFormatColumns() {
    }

    /**
     * 標識領域(7桁目)の文字。行がそこへ届かない場合は空白を返す。
     * 桁はバイト位置ではなく文字位置で数えるので、呼び出す側は桁と一致する写し方で復号しておく。
     */
    public static char indicator(String line) {
        return line.length() < INDICATOR_COLUMN ? ' ' : line.charAt(INDICATOR_COLUMN - 1);
    }

    /**
     * 本文(8〜72桁)。行が短ければ収まる範囲だけを返し、8桁目に届かない行には空文字を返す。
     * 一連番号領域と識別領域を落とすので、返り値の先頭は8桁目に対応する。
     */
    public static String body(String line) {
        if (line.length() < AREA_A_START) {
            return "";
        }
        return line.substring(AREA_A_START - 1, Math.min(line.length(), CONTENT_END));
    }
}
