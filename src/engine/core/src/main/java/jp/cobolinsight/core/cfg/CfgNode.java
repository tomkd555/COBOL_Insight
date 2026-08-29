package jp.cobolinsight.core.cfg;

import jp.cobolinsight.core.semantic.Statement;

import java.util.Objects;
import java.util.Optional;

/**
 * CFGの1ノード。STATEMENTノードは意味モデルの文への参照と所属手続き名を持つ。
 * id は同一グラフ内で一意で、生成順(意味モデルの定義順)に振る。GO TO正規化で複製された
 * ノードは originalNodeId に複製元のidを持ち、複製元のソース位置へたどれる。
 */
public final class CfgNode {

    private final int id;
    private final CfgNodeKind kind;
    private final Statement statement;
    private final String procedureName;
    private final Integer originalNodeId;

    public CfgNode(int id, CfgNodeKind kind, Statement statement, String procedureName) {
        this(id, kind, statement, procedureName, Optional.empty());
    }

    public CfgNode(int id, CfgNodeKind kind, Statement statement, String procedureName,
            Optional<Integer> originalNodeId) {
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statement = statement;
        this.procedureName = Objects.requireNonNull(procedureName, "procedureName");
        this.originalNodeId = Objects.requireNonNull(originalNodeId, "originalNodeId").orElse(null);
        if ((kind == CfgNodeKind.STATEMENT) != (statement != null)) {
            throw new IllegalArgumentException("STATEMENTノードだけが文を持つ: " + kind);
        }
    }

    public int id() {
        return id;
    }

    public CfgNodeKind kind() {
        return kind;
    }

    /** STATEMENTノードの文。ENTRY・EXITでは empty。 */
    public Optional<Statement> statement() {
        return Optional.ofNullable(statement);
    }

    /** 所属する段落・節の名前。ENTRY・EXITでは空文字列。 */
    public String procedureName() {
        return procedureName;
    }

    /** GO TO正規化で複製されたノードの複製元id。複製でなければ empty。 */
    public Optional<Integer> originalNodeId() {
        return Optional.ofNullable(originalNodeId);
    }

    @Override
    public String toString() {
        return "CfgNode[" + id + " " + kind + " " + procedureName + "]";
    }
}
