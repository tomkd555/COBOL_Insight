package jp.cobolinsight.engineapi.bms;

import java.util.Objects;

/**
 * DFHMDFが定義するフィールド。位置は1始まりの行・桁。名称なしのリテラルフィールドは
 * name を空文字列とする。attributes は ATTRB句のテキスト表現。
 */
public record BmsField(String name, int row, int column, int length, String attributes) {

    public BmsField {
        Objects.requireNonNull(name, "name");
        if (row < 1) {
            throw new IllegalArgumentException("row must be >= 1: " + row);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be >= 1: " + column);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0: " + length);
        }
        Objects.requireNonNull(attributes, "attributes");
    }
}
