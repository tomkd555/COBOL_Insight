package jp.cobolinsight.persistence.model;

/**
 * PARAGRAPH_EDGE表の1行(プログラム内の段落から段落への流れ)。kind は PERFORM・GOTO・
 * FALLTHROUGH のいずれかで、FALLTHROUGH は定義順で隣り合う段落への流下を表す。
 * toParagraph は飛び先の段落を特定できないとき null とし、そのときも toName に原文の名前を残す。
 * line は PERFORM文・GO TO文の行(流下は null)、seq は from の段落の出辺のうち何番目かである。
 */
public record ParagraphEdgeRecord(long id, long programSourceId, long fromParagraph,
        Long toParagraph, String toName, String kind, Integer line, int seq) {

    public ParagraphEdgeRecord {
        if (toName == null || toName.isBlank()) {
            throw new IllegalArgumentException("toName must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
