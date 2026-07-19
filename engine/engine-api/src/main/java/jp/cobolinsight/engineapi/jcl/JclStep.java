package jp.cobolinsight.engineapi.jcl;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** EXECステップ。condition はステップ単位のCOND句のテキスト表現。 */
public record JclStep(String name, JclExecKind execKind, String target, Optional<String> condition,
        List<JclDdStatement> ddStatements, SourcePosition position) {

    public JclStep {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(execKind, "execKind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        ddStatements = List.copyOf(ddStatements);
        Objects.requireNonNull(position, "position");
    }
}
