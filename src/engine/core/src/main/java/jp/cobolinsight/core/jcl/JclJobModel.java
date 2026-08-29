package jp.cobolinsight.core.jcl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JCLジョブ構造モデル。PROC展開・シンボリック解決後の構造を保持し、linker の入力となる。
 * condition はジョブ単位のCOND句のテキスト表現。
 */
public record JclJobModel(String jobName, String sourceFile, Optional<String> condition,
        List<JclStep> steps) {

    public JclJobModel {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        steps = List.copyOf(steps);
    }
}
