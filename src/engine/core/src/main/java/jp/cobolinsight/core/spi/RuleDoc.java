package jp.cobolinsight.core.spi;

/**
 * ルールの説明。「何を検出し、なぜ問題で、どう直すか」を利用者へ示す。
 * CLI の rules サブコマンド・SARIF の rules・GUI の設定画面がいずれもこれを引くため、
 * ルールの名称とカテゴリもここが単一の正である。
 *
 * <p>例(badExample・goodExample)は、対比になる短い断片を書けないルールがあるため空を許す。
 * 他の項目は必須とし、空のまま組み立てようとした時点で失敗させる。
 */
public record RuleDoc(
        String name,
        String category,
        String summary,
        String rationale,
        String detection,
        String remedy,
        String badExample,
        String goodExample) {

    public RuleDoc {
        name = requireText(name, "name");
        category = requireText(category, "category");
        summary = requireText(summary, "summary");
        rationale = requireText(rationale, "rationale");
        detection = requireText(detection, "detection");
        remedy = requireText(remedy, "remedy");
        badExample = badExample == null ? "" : badExample.strip();
        goodExample = goodExample == null ? "" : goodExample.strip();
    }

    /** 対比できる例を持つか。片方だけでは対比にならないため、両方が揃ったときだけ true。 */
    public boolean hasExample() {
        return !badExample.isEmpty() && !goodExample.isEmpty();
    }

    public static Builder named(String name, String category) {
        return new Builder(name, category);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RuleDoc." + field + " は空にできない");
        }
        return value.strip();
    }

    /** 引数8個の位置指定を避けるための組立器。検査は build() まで行わない。 */
    public static final class Builder {

        private final String name;
        private final String category;
        private String summary;
        private String rationale;
        private String detection;
        private String remedy;
        private String badExample = "";
        private String goodExample = "";

        private Builder(String name, String category) {
            this.name = name;
            this.category = category;
        }

        /** 何を検出するかを1文で。 */
        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        /** なぜ問題か。放置したときに何が起きるかを書く。 */
        public Builder rationale(String value) {
            this.rationale = value;
            return this;
        }

        /** 検出条件。何を対象外とするかも併せて書く。 */
        public Builder detection(String value) {
            this.detection = value;
            return this;
        }

        /** どう直すか。 */
        public Builder remedy(String value) {
            this.remedy = value;
            return this;
        }

        /** 該当する例と、直した例。 */
        public Builder example(String bad, String good) {
            this.badExample = bad;
            this.goodExample = good;
            return this;
        }

        public RuleDoc build() {
            return new RuleDoc(name, category, summary, rationale, detection, remedy,
                    badExample, goodExample);
        }
    }
}
