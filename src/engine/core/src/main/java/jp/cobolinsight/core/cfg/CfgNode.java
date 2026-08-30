package jp.cobolinsight.core.cfg;

import jp.cobolinsight.core.semantic.Statement;

import java.util.Objects;
import java.util.Optional;

/**
 * A single node of the CFG. A STATEMENT node holds a reference to a statement in the semantic
 * model and the name of the procedure it belongs to. id is unique within the same graph and is
 * assigned in generation order (the definition order in the semantic model). A node duplicated by
 * GO TO normalization holds the id of its original in originalNodeId, so the original's source
 * location can be traced.
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

    /** The statement of a STATEMENT node. empty for ENTRY/EXIT. */
    public Optional<Statement> statement() {
        return Optional.ofNullable(statement);
    }

    /** The name of the paragraph/section this node belongs to. An empty string for ENTRY/EXIT. */
    public String procedureName() {
        return procedureName;
    }

    /** The id of the original node for a node duplicated by GO TO normalization. empty if not a duplicate. */
    public Optional<Integer> originalNodeId() {
        return Optional.ofNullable(originalNodeId);
    }

    @Override
    public String toString() {
        return "CfgNode[" + id + " " + kind + " " + procedureName + "]";
    }
}
