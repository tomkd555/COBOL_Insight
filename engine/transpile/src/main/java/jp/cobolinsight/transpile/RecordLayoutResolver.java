package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.Occurs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * データ項目木からレコードレイアウト({@link LayoutField})を解決する。項目長は
 * {@link PictureType#byteLength()}、累積オフセットは先行同順兄弟の byteLength×occurs の積算で求める。
 * REDEFINES 対象は元項目と同一開始オフセットを共有し、後続オフセットには加算しない。集団項目長は
 * 加算対象(REDEFINES でない)の子の総和。副作用なし・決定論的(子はリスト順に処理する)。
 */
public final class RecordLayoutResolver {

    private RecordLayoutResolver() {
    }

    /** 01(または任意レベル)の項目を根としてレイアウトを解決する。根の offset は0。 */
    public static LayoutField resolve(DataItem record) {
        return resolveItem(record, 0);
    }

    private static LayoutField resolveItem(DataItem item, int startOffset) {
        int occurs = item.occurs().map(Occurs::maxTimes).orElse(1);
        if (item.picture().isPresent()) {
            PictureType pt = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            return new LayoutField(item.name(), item.level(), startOffset, pt.byteLength(), occurs,
                    Optional.of(pt), item.redefines(), item.conditionNames(), List.of());
        }
        List<LayoutField> children = new ArrayList<>();
        Map<String, Integer> siblingOffset = new HashMap<>();
        int cursor = startOffset;
        for (DataItem child : item.children()) {
            int childStart;
            if (child.redefines().isPresent()) {
                Integer target = siblingOffset.get(child.redefines().get());
                childStart = target != null ? target : cursor;
            } else {
                childStart = cursor;
            }
            LayoutField resolved = resolveItem(child, childStart);
            children.add(resolved);
            siblingOffset.put(child.name(), childStart);
            if (child.redefines().isEmpty()) {
                cursor += resolved.totalSpan();
            }
        }
        int groupLength = cursor - startOffset;
        return new LayoutField(item.name(), item.level(), startOffset, groupLength, occurs,
                Optional.empty(), item.redefines(), item.conditionNames(), children);
    }
}
