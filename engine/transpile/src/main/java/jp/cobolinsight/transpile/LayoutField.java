package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.semantic.ConditionName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * レコードレイアウト上の1項目。offset はレコード先頭からの累積バイトオフセット、byteLength は
 * 1要素分の格納バイト長(集団項目は子の総和、基本項目は PICTURE+USAGE から算出)、occurs は
 * OCCURS 回数(無指定は1)。totalSpan() は byteLength×occurs で、後続同順兄弟のオフセット算出に使う。
 * pictureType は基本項目のとき present。redefines は REDEFINES 対象名。conditionNames は
 * この項目に紐付く88レベル述語。children は集団項目の直下項目(基本項目では空)。
 * OCCURS 項目の children の offset は先頭要素(index 0)基準の絶対オフセットで、
 * index 番目の要素は offset + index×byteLength に位置する。
 */
public record LayoutField(String name, int level, int offset, int byteLength, int occurs,
        Optional<PictureType> pictureType, Optional<String> redefines,
        List<ConditionName> conditionNames, List<LayoutField> children) {

    public LayoutField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be >= 0: " + byteLength);
        }
        if (occurs < 1) {
            throw new IllegalArgumentException("occurs must be >= 1: " + occurs);
        }
        Objects.requireNonNull(pictureType, "pictureType");
        Objects.requireNonNull(redefines, "redefines");
        conditionNames = List.copyOf(conditionNames);
        children = List.copyOf(children);
    }

    /** この項目が占める総バイト長(OCCURS 全要素分)。 */
    public int totalSpan() {
        return byteLength * occurs;
    }

    /** この項目を根とする部分木から、名前で1件を探す(自身を含む)。 */
    public Optional<LayoutField> find(String targetName) {
        if (name.equals(targetName)) {
            return Optional.of(this);
        }
        for (LayoutField child : children) {
            Optional<LayoutField> found = child.find(targetName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
