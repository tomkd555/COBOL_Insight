package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CallKind;
import jp.cobolinsight.core.semantic.CallRelation;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Occurs;
import jp.cobolinsight.core.semantic.NestedBranches;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp.cobol.common.model.ProcedureName;
import org.eclipse.lsp.cobol.common.model.tree.CodeBlockUsageNode;
import org.eclipse.lsp.cobol.common.model.tree.CopyNode;
import org.eclipse.lsp.cobol.common.model.tree.DivisionNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateWhenNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateWhenOtherNode;
import org.eclipse.lsp.cobol.common.model.tree.ExitNode;
import org.eclipse.lsp.cobol.common.model.tree.FileEntryNode;
import org.eclipse.lsp.cobol.common.model.tree.GoBackNode;
import org.eclipse.lsp.cobol.common.model.tree.GoToNode;
import org.eclipse.lsp.cobol.common.model.tree.IfElseNode;
import org.eclipse.lsp.cobol.common.model.tree.IfNode;
import org.eclipse.lsp.cobol.common.model.tree.LiteralNode;
import org.eclipse.lsp.cobol.common.model.tree.Node;
import org.eclipse.lsp.cobol.common.model.tree.OpenStatementNode;
import org.eclipse.lsp.cobol.common.model.tree.ParagraphNode;
import org.eclipse.lsp.cobol.common.model.tree.ParagraphsNode;
import org.eclipse.lsp.cobol.common.model.tree.PerformNode;
import org.eclipse.lsp.cobol.common.model.tree.PerformUntilNode;
import org.eclipse.lsp.cobol.common.model.tree.ProcedureDivisionBodyNode;
import org.eclipse.lsp.cobol.common.model.tree.ProcedureDivisionNode;
import org.eclipse.lsp.cobol.common.model.tree.ProcedureSectionNode;
import org.eclipse.lsp.cobol.common.model.tree.ProgramNode;
import org.eclipse.lsp.cobol.common.model.tree.SentenceNode;
import org.eclipse.lsp.cobol.common.model.tree.StopNode;
import org.eclipse.lsp.cobol.common.model.tree.SubroutineNameNode;
import org.eclipse.lsp.cobol.common.model.tree.SubroutineNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.ElementaryItemNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.ElementaryNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.MultiTableDataNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.OccursClause;
import org.eclipse.lsp.cobol.common.model.tree.variable.QualifiedReferenceNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.StandAloneDataItemNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.TableDataNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.UsageFormat;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableDefinitionNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableUsageNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableWithLevelNode;
import org.eclipse.lsp.cobol.common.model.tree.variables.ConditionDataNameNode;
import org.eclipse.lsp.cobol.common.model.variables.DivisionType;
import org.eclipse.lsp.cobol.common.model.tree.ExecCicsAbendNode;
import org.eclipse.lsp.cobol.implicitDialects.cics.nodes.ExecCicsHandleNode;
import org.eclipse.lsp.cobol.implicitDialects.cics.nodes.ExecCicsNode;
import org.eclipse.lsp.cobol.implicitDialects.cics.nodes.ExecCicsReturnNode;
import org.eclipse.lsp.cobol.implicitDialects.sql.node.ExecSqlNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Che4z's AST (plus CST supplementation) into engine-api's normalized semantic model.
 *
 * <p>Syntax for which Che4z does not build a dedicated node is filled in from the original text.
 * The IF condition and the EVALUATE selector are held as the original text spanning their child
 * nodes' combined range, and the verb is looked up via the CST index. Definitions Che4z implicitly
 * inserts (such as SQLCA) appear under a URI with no real file, so they are excluded from the
 * semantic model.
 */
final class SemanticModelMapper {

    /** Extracts the EXEC CICS operand {@code NAME(value)}. Does not handle nested parentheses. */
    private static final Pattern OPERAND_PATTERN =
            Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*\\(\\s*([^()]*?)\\s*\\)");

    private final String sourceFilePath;
    private final SourceTexts texts;
    private final CstCapture cstCapture;

    private final List<CallRelation> calls = new ArrayList<>();
    private final List<PerformRelation> performs = new ArrayList<>();
    private final List<EmbeddedBlock> embeddedBlocks = new ArrayList<>();
    /** File name (case ignored) to the modes the PROCEDURE DIVISION opens it in. */
    private final Map<String, Set<FileAccess>> fileAccesses =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private String programId;
    /** Each EXEC block a COPY brings into the PROCEDURE DIVISION, to that COPY statement. */
    private final Map<Node, CopyNode> copyOfBlock = new IdentityHashMap<>();
    /** Each COPY statement of the PROCEDURE DIVISION, to its EXEC blocks in copybook order. */
    private final Map<CopyNode, List<Node>> blocksOfCopy = new IdentityHashMap<>();
    /** The COPY statements Che4z hangs at procedure level, by the procedure they stand in. */
    private final Map<Node, List<CopyNode>> copiesOwnedBy = new IdentityHashMap<>();

    SemanticModelMapper(String sourceFilePath, SourceTexts texts, CstCapture cstCapture) {
        this.sourceFilePath = sourceFilePath;
        this.texts = texts;
        this.cstCapture = cstCapture;
    }

    CobolSemanticModel map(ProgramNode program) {
        programId = program.getProgramName();
        List<DataItem> dataItems = mapDataDivision(program);
        List<Procedure> procedures = mapProcedureDivision(program);
        collectOpenModes(program);
        return new CobolSemanticModel(programId, sourceFilePath, dataItems, procedures, calls,
                performs, embeddedBlocks,
                cstCapture == null ? List.of() : cstCapture.copyExpansions(),
                cstCapture == null ? List.of() : cstCapture.copyInlineExpansions(),
                mapFileControl(program));
    }

    // ---- FILE-CONTROL: SELECT ... ASSIGN, and how the file is opened ----

    /**
     * {@code ASSIGN TO UT-S-INFILE}, {@code ASSIGN TO 'INFILE'} and the plain name form, with the
     * {@code DYNAMIC}, {@code EXTERNAL} and {@code VARYING} the clause may carry before the name
     * stepped over — they say how the assignment works, not what it names.
     */
    private static final Pattern ASSIGN_CLAUSE = Pattern.compile(
            "ASSIGN\\s+(?:TO\\s+)?((?:(?:DYNAMIC|EXTERNAL|VARYING)\\s+)*)"
                    + "(?:'([^']*)'|\"([^\"]*)\"|([A-Za-z0-9$#@_-]+))",
            Pattern.CASE_INSENSITIVE);

    /**
     * The device classes an ASSIGN may name in place of a file. A clause that names one of these
     * and nothing after it points at no DD, so the entry carries none.
     */
    private static final Set<String> DEVICE_CLASSES = Set.of("DISK", "DISC", "PRINTER", "TAPE",
            "CARD", "KEYBOARD", "DISPLAY", "UT", "UR", "DA", "AS", "SYSIN", "SYSOUT", "SYSLIST",
            "SYSLST", "SYSPUNCH", "CONSOLE", "COMMITMENT-CONTROL");

    private static final Pattern ORGANIZATION_CLAUSE = Pattern.compile(
            "ORGANIZATION\\s+(?:IS\\s+)?"
                    + "((?:LINE|RECORD|RECORD\\s+BINARY|BINARY)\\s+SEQUENTIAL"
                    + "|SEQUENTIAL|INDEXED|RELATIVE)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The SELECT entries of the program, each with the DD name its ASSIGN clause points at and the
     * modes the PROCEDURE DIVISION opens it in. An entry Che4z inserted implicitly names no file
     * of the source and is left out.
     */
    private List<FileDefinition> mapFileControl(ProgramNode program) {
        Map<String, FileDefinition> byName = new LinkedHashMap<>();
        for (Node node : program.getDepthFirstList(FileEntryNode.class::isInstance)) {
            FileEntryNode entry = (FileEntryNode) node;
            if (isImplicit(entry) || entry.getFileName() == null
                    || entry.getFileName().isBlank()) {
                continue;
            }
            String clause = entry.getFileControlClause() == null ? ""
                    : entry.getFileControlClause().replaceAll("\\s+", " ");
            byName.putIfAbsent(entry.getFileName(), new FileDefinition(entry.getFileName(),
                    ddNameOf(clause), organisationOf(clause),
                    fileAccesses.getOrDefault(entry.getFileName(), Set.of()),
                    positionOf(entry.getLocality())));
        }
        return List.copyOf(byName.values());
    }

    /**
     * The DD name an ASSIGN clause points at, uppercased. A name written in the system form
     * ({@code UT-S-INFILE}, {@code SYS010-UT-INFILE}) names the DD in its last qualifier; a literal
     * is the DD name whole, hyphens and all, because the literal is what the system reads. A clause
     * naming only a device class ({@code ASSIGN TO DISK}) points at no DD and yields none.
     *
     * <p>Neither does {@code ASSIGN TO DYNAMIC WS-TAPE-NAME} or the {@code VARYING} form: what
     * follows is a data item holding the DD name at run time, so which data set the program reads is
     * not in the source. Reading the data item's own name as a DD would put a file in the graph that
     * the JCL never names. {@code EXTERNAL} is different — the name after it is the name.
     */
    private static Optional<String> ddNameOf(String clause) {
        Matcher matcher = ASSIGN_CLAUSE.matcher(clause);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String modifiers = matcher.group(1).toUpperCase(Locale.ROOT);
        if (modifiers.contains("DYNAMIC") || modifiers.contains("VARYING")) {
            return Optional.empty();
        }
        String literal = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (literal != null) {
            String quoted = literal.trim();
            return quoted.isEmpty() ? Optional.empty()
                    : Optional.of(quoted.toUpperCase(Locale.ROOT));
        }
        String name = matcher.group(4);
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (DEVICE_CLASSES.contains(upper)) {
            return Optional.empty();
        }
        String last = upper.substring(upper.lastIndexOf('-') + 1);
        return last.isBlank() ? Optional.empty() : Optional.of(last);
    }

    private static Optional<String> organisationOf(String clause) {
        Matcher matcher = ORGANIZATION_CLAUSE.matcher(clause);
        return matcher.find()
                ? Optional.of(matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT))
                : Optional.empty();
    }

    /**
     * Records the mode of every OPEN. Che4z builds one node per file named, so an OPEN naming
     * several files applies its mode to each of them with no further work here.
     */
    private void collectOpenModes(ProgramNode program) {
        for (Node node : program.getDepthFirstList(OpenStatementNode.class::isInstance)) {
            OpenStatementNode open = (OpenStatementNode) node;
            if (open.getFilename() == null || open.getFilename().getName() == null) {
                continue;
            }
            addFileAccess(open.getFilename().getName(), switch (open.getFileOperationKind()) {
                case INPUT -> FileAccess.INPUT;
                case OUTPUT -> FileAccess.OUTPUT;
                case I_O -> FileAccess.IO;
                case EXTEND -> FileAccess.EXTEND;
            });
        }
    }

    private void addFileAccess(String fileName, FileAccess access) {
        fileAccesses.computeIfAbsent(fileName, k -> EnumSet.noneOf(FileAccess.class)).add(access);
    }

    // ---- Data division ----

    private List<DataItem> mapDataDivision(ProgramNode program) {
        List<DataItem> items = new ArrayList<>();
        for (Node child : program.getChildren()) {
            if (child instanceof DivisionNode division
                    && division.getDivisionType() == DivisionType.DATA_DIVISION) {
                collectDataItems(division, items);
                // DECLARE CURSOR and DCLGEN DECLARE TABLE live in WORKING-STORAGE as often as
                // in the PROCEDURE DIVISION; the SQL rules must see them either way.
                division.getDepthFirstStream()
                        .filter(ExecSqlNode.class::isInstance)
                        .forEach(this::mapEmbeddedSql);
            }
        }
        return items;
    }

    private void collectDataItems(Node parent, List<DataItem> out) {
        for (Node child : parent.getChildren()) {
            if (isImplicit(child)) {
                continue;
            }
            if (child instanceof VariableWithLevelNode variable
                    && !(child instanceof ConditionDataNameNode)
                    && isMappableLevel(variable.getLevel())) {
                out.add(mapDataItem(variable));
            } else {
                collectDataItems(child, out);
            }
        }
    }

    private DataItem mapDataItem(VariableWithLevelNode variable) {
        Optional<String> picture = Optional.empty();
        Optional<String> usage = Optional.empty();
        if (variable instanceof ElementaryNode elementary) {
            picture = Optional.ofNullable(elementary.getPicClause()).map(String::trim)
                    .filter(p -> !p.isEmpty());
            usage = usageOf(elementary.getUsageFormat());
        }
        Optional<String> value = valueOf(variable);
        Optional<Occurs> occurs = occursOf(variable);
        Optional<String> redefines = Optional.empty();
        if (variable.isRedefines()) {
            redefines = variable.getChildren().stream()
                    .filter(VariableUsageNode.class::isInstance)
                    .map(n -> ((VariableUsageNode) n).getName())
                    .findFirst();
        }
        List<ConditionName> conditionNames = new ArrayList<>();
        List<DataItem> children = new ArrayList<>();
        for (Node child : variable.getChildren()) {
            if (isImplicit(child)) {
                continue;
            }
            if (child instanceof ConditionDataNameNode condition) {
                mapConditionName(condition).ifPresent(conditionNames::add);
            } else if (child instanceof VariableWithLevelNode nested
                    && isMappableLevel(nested.getLevel())) {
                children.add(mapDataItem(nested));
            }
        }
        return new DataItem(variable.getLevel(), variable.getName(), picture, usage, value,
                redefines, occurs, conditionNames, children, positionOf(variable.getLocality()));
    }

    /**
     * Level numbers that count as data items: hierarchical items 01-49, 66 (RENAMES), and
     * 77 (standalone items). An 88 condition name has no storage of its own and is kept as its
     * parent item's conditionNames rather than as a data item.
     */
    private static boolean isMappableLevel(int level) {
        return (level >= 1 && level <= 49) || level == 66 || level == 77;
    }

    /** Retrieves the VALUE clause. Absence of VALUE comes back as an empty string, so it is treated as empty/absent. */
    private static Optional<String> valueOf(VariableWithLevelNode variable) {
        String value = null;
        if (variable instanceof ElementaryItemNode elementary) {
            value = elementary.getValue();
        } else if (variable instanceof TableDataNameNode table) {
            value = table.getValue();
        } else if (variable instanceof StandAloneDataItemNode standAlone) {
            value = standAlone.getValue();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private static Optional<String> usageOf(UsageFormat usageFormat) {
        if (usageFormat == null || usageFormat == UsageFormat.UNDEFINED) {
            return Optional.empty();
        }
        return Optional.of(usageFormat.name().replace('_', '-'));
    }

    private static Optional<Occurs> occursOf(VariableWithLevelNode variable) {
        if (variable instanceof TableDataNameNode table) {
            int times = table.getOccursTimes();
            return Optional.of(new Occurs(times, times, Optional.empty()));
        }
        if (variable instanceof MultiTableDataNameNode multi) {
            OccursClause clause = multi.getOccursClause();
            if (clause != null && clause.getFrom() != null) {
                int from = clause.getFrom();
                int to = clause.getTo() != null ? clause.getTo() : from;
                return Optional.of(new Occurs(Math.min(from, to), Math.max(from, to),
                        Optional.empty()));
            }
        }
        return Optional.empty();
    }

    private Optional<ConditionName> mapConditionName(ConditionDataNameNode condition) {
        List<String> values = condition.getValueIntervals().stream()
                .map(interval -> interval.getTo() == null ? interval.getFrom()
                        : interval.getFrom() + " THRU " + interval.getTo())
                .toList();
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ConditionName(condition.getName(), values,
                positionOf(condition.getLocality())));
    }

    // ---- Procedure division ----

    /**
     * Name of the procedure that holds the sentences written straight under the PROCEDURE
     * DIVISION header. The blank keeps it clear of every paragraph name a program can declare,
     * because a COBOL word holds no blank.
     */
    static final String IMPLICIT_PROCEDURE_NAME = "PROCEDURE DIVISION";

    private List<Procedure> mapProcedureDivision(ProgramNode program) {
        assignCopiedBlocks(program);
        List<Procedure> procedures = new ArrayList<>();
        collectProcedures(program, procedures, Optional.empty());
        return procedures;
    }

    private void collectProcedures(Node parent, List<Procedure> out, Optional<String> section) {
        List<Node> sentences = parent.getChildren().stream()
                .filter(SentenceNode.class::isInstance).toList();
        boolean ownBlocks = parent instanceof ProcedureDivisionBodyNode
                && (parent.getChildren().stream().anyMatch(SemanticModelMapper::isExecBlock)
                        || copiesOwnedBy.getOrDefault(parent, List.of()).stream()
                                .anyMatch(blocksOfCopy::containsKey));
        if ((!sentences.isEmpty() || ownBlocks) && !(parent instanceof ProcedureSectionNode)) {
            // Sentences that hang off the PROCEDURE DIVISION itself, before any paragraph or
            // section header, belong to no procedure. Map them as one implicit procedure, or the
            // semantic model, the CFG and data flow never see them. A section's own sentences are
            // already mapped with the section, so a section is left out here.
            out.add(new Procedure(IMPLICIT_PROCEDURE_NAME, ProcedureKind.PARAGRAPH, section,
                    mapStatements(parent, IMPLICIT_PROCEDURE_NAME),
                    sentences.isEmpty() ? rangeOf(parent.getLocality())
                            : sentenceExtent(parent, sentences)));
        }
        for (Node child : parent.getChildren()) {
            if (child instanceof ProcedureSectionNode sectionNode) {
                out.add(mapProcedure(sectionNode, sectionNode.getName(), ProcedureKind.SECTION,
                        Optional.empty()));
                collectProcedures(child, out, Optional.of(sectionNode.getName()));
            } else if (child instanceof ParagraphNode paragraph) {
                out.add(mapProcedure(paragraph, paragraph.getName(), ProcedureKind.PARAGRAPH,
                        section));
            } else {
                collectProcedures(child, out, section);
            }
        }
    }

    private Procedure mapProcedure(Node block, String name, ProcedureKind kind,
            Optional<String> section) {
        return new Procedure(name, kind, section, mapStatements(block, name),
                rangeOf(block.getLocality()));
    }

    private List<Statement> mapStatements(Node block, String procedureName) {
        List<Node> children = new ArrayList<>();
        for (Node child : block.getChildren()) {
            if (child instanceof SentenceNode) {
                children.addAll(child.getChildren());
            } else if (isExecBlock(child)) {
                children.add(child);
            }
        }
        children.addAll(copiesOwnedBy.getOrDefault(block, List.of()));
        List<Statement> statements = new ArrayList<>();
        for (Node statementNode : inWrittenOrder(children)) {
            if (isStatementNode(statementNode)) {
                addStatement(statementNode, procedureName, statements);
            }
        }
        return statements;
    }

    /**
     * Pairs each EXEC block a COPY brings into the PROCEDURE DIVISION with that COPY statement.
     * Che4z's dialects add such a block to the tree after the parse, at the first place its
     * copybook position maps to: the last child of a sentence, under a CopyNode, a direct child of
     * the paragraph, or nested inside an earlier block from the same copybook line. The blocks of
     * every inclusion of one copybook gather at one such place, which may lie in another paragraph
     * or branch. Each inclusion still brings one block per copybook position, so the n-th block
     * at a copybook position belongs to the n-th COPY of that copybook in written order. A COPY
     * held by a node other than the statement containers this mapper orders, and a block left over
     * once each COPY of its copybook has one, stay where Che4z put them; so does a block inside a
     * paragraph a copybook declares.
     *
     * <p>A COPY written among a paragraph's sentences can hang off the PROCEDURE DIVISION or its
     * section in place of the paragraph, as the last COPY of the program does. A COPY Che4z hangs
     * at procedure level belongs to the procedure whose header comes last before it.
     */
    private void assignCopiedBlocks(ProgramNode program) {
        List<Node> blocks = new ArrayList<>();
        List<CopyNode> copies = new ArrayList<>();
        List<Node> headers = new ArrayList<>();
        collectCopied(program, false, blocks, copies, headers);
        copies.sort(Comparator.comparingLong(SemanticModelMapper::key));
        headers.sort(Comparator.comparingLong(SemanticModelMapper::key));
        for (CopyNode copy : copies) {
            if (isProcedureLevel(copy.getParent())) {
                Node owner = null;
                for (Node header : headers) {
                    if (key(header) <= key(copy)) {
                        owner = header;
                    }
                }
                if (owner == null) {
                    owner = copy.getParent();
                    while (owner != null && !(owner instanceof ProcedureDivisionBodyNode)) {
                        owner = owner.getParent();
                    }
                }
                if (owner != null) {
                    copiesOwnedBy.computeIfAbsent(owner, k -> new ArrayList<>()).add(copy);
                }
            }
        }
        Map<String, List<CopyNode>> copiesOf = new HashMap<>();
        for (CopyNode copy : copies) {
            copiesOf.computeIfAbsent(fileOf(copy.getUri()), k -> new ArrayList<>()).add(copy);
        }
        Map<String, List<Node>> atPosition = new LinkedHashMap<>();
        for (Node block : blocks) {
            atPosition.computeIfAbsent(fileOf(block.getLocality().getUri()) + "\n" + key(block),
                    k -> new ArrayList<>()).add(block);
        }
        for (List<Node> same : atPosition.values()) {
            List<CopyNode> inclusions = copiesOf.getOrDefault(
                    fileOf(same.get(0).getLocality().getUri()), List.of());
            for (int i = 0; i < Math.min(same.size(), inclusions.size()); i++) {
                copyOfBlock.put(same.get(i), inclusions.get(i));
                blocksOfCopy.computeIfAbsent(inclusions.get(i), k -> new ArrayList<>())
                        .add(same.get(i));
            }
        }
        blocksOfCopy.values().forEach(list -> list.sort(Comparator.comparingLong(
                SemanticModelMapper::key)));
    }

    /**
     * The copied EXEC blocks, the program's COPY statements and the procedure headers the program
     * file writes, of the PROCEDURE DIVISION under {@code node}. Nested programs, the arguments of
     * a CALL and the paragraphs and sections a copybook declares are left out.
     */
    private void collectCopied(Node node, boolean inProcedure, List<Node> blocks,
            List<CopyNode> copies, List<Node> headers) {
        for (Node child : node.getChildren()) {
            if (child instanceof ProgramNode || child instanceof SubroutineNode) {
                continue;
            }
            boolean in = inProcedure || child instanceof ProcedureDivisionNode;
            if (child instanceof ParagraphNode || child instanceof ProcedureSectionNode) {
                if (!inProgramFile(child.getLocality())) {
                    continue;
                }
                headers.add(child);
            }
            if (in && child instanceof CopyNode copy && copy.getUri() != null
                    && inProgramFile(copy.getLocality())
                    && (isContainer(copy.getParent()) || isProcedureLevel(copy.getParent()))) {
                copies.add(copy);
            } else if (in && isExecBlock(child) && child.getLocality() != null
                    && child.getLocality().getRange() != null
                    && !inProgramFile(child.getLocality())) {
                blocks.add(child);
            }
            collectCopied(child, in, blocks, copies, headers);
        }
    }

    /** Whether the children of {@code node} below procedure level go through {@link #inWrittenOrder}. */
    private static boolean isContainer(Node node) {
        return node instanceof SentenceNode
                || node instanceof IfNode || node instanceof IfElseNode
                || node instanceof EvaluateNode || node instanceof EvaluateWhenOtherNode
                || node instanceof PerformNode perform && perform.isInline();
    }

    /** Whether a COPY hung off {@code node} stands among some procedure's sentences. */
    private static boolean isProcedureLevel(Node node) {
        return node instanceof ParagraphNode || node instanceof ProcedureSectionNode
                || node instanceof ProcedureDivisionBodyNode || node instanceof ParagraphsNode;
    }

    private List<Node> inWrittenOrder(Node container) {
        return inWrittenOrder(container.getChildren());
    }

    /**
     * {@code children} in the order the program writes them. A node of the program file stands
     * at its own position. The EXEC blocks paired with a COPY stand at that COPY statement in
     * copybook order, and the CopyNode itself drops out; a block paired with a COPY elsewhere
     * drops out here. A statement Che4z expanded from a copybook in line stands at the first COPY
     * here that names its copybook, comes after the program-file node before it and has blocks
     * placed at it, at its copybook position: it keeps its order among that COPY's blocks. Any
     * other node keeps its place after the node Che4z put before it, and an unpaired block Che4z
     * nested inside a block follows that block.
     */
    private List<Node> inWrittenOrder(List<Node> children) {
        List<Node> nodes = new ArrayList<>();
        List<long[]> keys = new ArrayList<>();
        List<CopyNode> copiesHere = children.stream().filter(CopyNode.class::isInstance)
                .map(CopyNode.class::cast).filter(blocksOfCopy::containsKey).toList();
        long[] last = {-1, 0};
        long[] previous = last;
        for (Node child : children) {
            if (child instanceof CopyNode copy) {
                for (Node block : blocksOfCopy.getOrDefault(copy, List.of())) {
                    long[] at = {key(copy), key(block)};
                    for (Node node : withUnpairedNested(block)) {
                        nodes.add(node);
                        keys.add(at);
                    }
                }
                continue;
            }
            if (copyOfBlock.containsKey(child)) {
                continue;
            }
            long[] at = previous;
            if (inProgramFile(child.getLocality())) {
                last = new long[] {key(child), 0};
                at = last;
            } else if (!isExecBlock(child) && child.getLocality() != null) {
                String copybook = fileOf(child.getLocality().getUri());
                long after = last[0];
                at = copiesHere.stream()
                        .filter(copy -> fileOf(copy.getUri()).equals(copybook) && key(copy) > after)
                        .mapToLong(SemanticModelMapper::key).min()
                        .stream().mapToObj(copy -> new long[] {copy, key(child)})
                        .findFirst().orElse(previous);
            }
            previous = at;
            for (Node node : isExecBlock(child) ? withUnpairedNested(child) : List.of(child)) {
                nodes.add(node);
                keys.add(at);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingLong(i -> keys.get(i)[0])
                .thenComparingLong(i -> keys.get(i)[1]));
        return order.stream().map(nodes::get).toList();
    }

    /** {@code block} followed by the EXEC blocks nested inside it that no COPY was paired with. */
    private List<Node> withUnpairedNested(Node block) {
        List<Node> out = new ArrayList<>(List.of(block));
        block.getDepthFirstStream().skip(1).filter(SemanticModelMapper::isExecBlock)
                .filter(nested -> !copyOfBlock.containsKey(nested)).forEach(out::add);
        return out;
    }

    /** A node's start as one number that orders positions within one file. */
    private static long key(Node node) {
        Locality locality = node.getLocality();
        if (locality == null || locality.getRange() == null) {
            return -1;
        }
        org.eclipse.lsp4j.Position start = locality.getRange().getStart();
        return start.getLine() * 1_000_000L + start.getCharacter();
    }

    private boolean inProgramFile(Locality locality) {
        return locality != null && locality.getRange() != null
                && fileOf(locality.getUri()).equals(sourceFilePath);
    }

    /**
     * The extent of the sentences hanging off {@code block}: the first sentence's start to the
     * last one's end. Falls back to the block itself when the two do not form a range within one
     * file, which a COPY member inside the PROCEDURE DIVISION can cause.
     */
    private SourceRange sentenceExtent(Node block, List<Node> sentences) {
        SourcePosition start = rangeOf(sentences.get(0).getLocality()).start();
        SourcePosition end = rangeOf(sentences.get(sentences.size() - 1).getLocality()).end();
        if (!start.file().equals(end.file()) || end.line() < start.line()
                || (end.line() == start.line() && end.column() < start.column())) {
            return rangeOf(block.getLocality());
        }
        return new SourceRange(start, end);
    }

    /**
     * Maps one statement node into {@code out}. A CALL is followed there by the EXEC blocks Che4z
     * read into its USING list, each as the statement it is.
     */
    private void addStatement(Node node, String procedureName, List<Statement> out) {
        out.add(mapStatement(node, procedureName));
        if (node instanceof SubroutineNode call) {
            for (Node block : absorbedExecBlocks(call)) {
                out.add(mapStatement(block, procedureName));
            }
        }
    }

    private Statement mapStatement(Node node, String procedureName) {
        if (node instanceof IfNode ifNode) {
            return mapIf(ifNode, procedureName);
        }
        if (node instanceof EvaluateNode evaluateNode) {
            return mapEvaluate(evaluateNode, procedureName);
        }
        if (node instanceof PerformNode perform && perform.isInline()) {
            return mapInlinePerform(perform, procedureName);
        }
        if (node instanceof PerformNode perform) {
            performs.add(performRelation(procedureName, perform));
            return simple("PERFORM", node);
        }
        if (node instanceof GoToNode goTo) {
            return new GoToStatement(goTo.getTargets(), dependingOn(goTo),
                    rangeOf(goTo.getLocality()), targetSections(goTo));
        }
        if (node instanceof SubroutineNode call) {
            Locality locality = callLocality(call);
            mapCall(call, locality);
            // What an ON EXCEPTION or ON OVERFLOW phrase, or its NOT form, writes.
            collectNested(call, procedureName, true);
            return simple("CALL", locality);
        }
        if (node instanceof ExecSqlNode) {
            mapEmbeddedSql(node);
            return simple("EXEC SQL", node);
        }
        if (node instanceof ExecCicsNode || node instanceof ExecCicsReturnNode
                || node instanceof ExecCicsHandleNode || node instanceof ExecCicsAbendNode) {
            mapEmbeddedCics(node);
            return simple("EXEC CICS", node);
        }
        if (node instanceof StopNode) {
            return simple("STOP", node);
        }
        if (node instanceof GoBackNode) {
            return simple("GOBACK", node);
        }
        if (node instanceof ExitNode) {
            return simple("EXIT", node);
        }
        String verb = resolveVerb(node);
        if ("SORT".equals(verb) || "MERGE".equals(verb)) {
            String text = NestedBranches.withoutCommentLines(texts.textOf(node.getLocality()))
                    .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            collectSortProcedures(text, node, procedureName);
            collectSortFiles(text);
        }
        collectNested(node, procedureName, false);
        return simple(verb, node);
    }

    private static final Pattern SORT_PROCEDURE = Pattern.compile(
            "(?:INPUT|OUTPUT)\\s+PROCEDURE\\s+(?:IS\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)"
                    + "(?:\\s+(?:THRU|THROUGH)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*))?");

    /**
     * A SORT or MERGE runs its INPUT PROCEDURE and OUTPUT PROCEDURE the way a PERFORM THRU runs
     * a range, so each is recorded as a PERFORM relation on the statement's range. Without this
     * the procedures look unreferenced and their statements unreachable.
     */
    private void collectSortProcedures(String text, Node node, String procedureName) {
        Matcher matcher = SORT_PROCEDURE.matcher(text);
        while (matcher.find()) {
            performs.add(new PerformRelation(procedureName, matcher.group(1),
                    Optional.ofNullable(matcher.group(2)), rangeOf(node.getLocality())));
        }
    }

    /** The words of a SORT or MERGE that close the file list a USING or a GIVING opens. */
    private static final Set<String> SORT_KEYWORDS = Set.of("SORT", "MERGE", "USING", "GIVING",
            "ON", "ASCENDING", "DESCENDING", "KEY", "WITH", "DUPLICATES", "IN", "ORDER", "INPUT",
            "OUTPUT", "PROCEDURE", "IS", "THRU", "THROUGH", "SEQUENCE", "COLLATING", "END-SORT",
            "END-MERGE");

    /**
     * A SORT or MERGE reads the files of its USING and writes those of its GIVING, so each side
     * counts as an OPEN of that mode. Neither Che4z node carries the file names, so they are read
     * off the statement text.
     */
    private void collectSortFiles(String text) {
        FileAccess mode = null;
        for (String token : text.split("[\\s,;.]+")) {
            if ("USING".equals(token)) {
                mode = FileAccess.INPUT;
            } else if ("GIVING".equals(token)) {
                mode = FileAccess.OUTPUT;
            } else if (SORT_KEYWORDS.contains(token)) {
                mode = null;
            } else if (mode != null && !token.isBlank()) {
                addFileAccess(token, mode);
            }
        }
    }

    /**
     * Picks up what the conditional clause of a statement that is otherwise mapped as a flat
     * statement writes (e.g. READ ... NOT INVALID KEY PERFORM ..., COMPUTE ... ON SIZE ERROR
     * PERFORM ..., READ ... AT END EXEC CICS LINK ... END-EXEC): the relation of a PERFORM, the
     * relation of a CALL, and the block of an EXEC CICS or EXEC SQL. The statement itself is still
     * mapped as a SimpleStatement; only the relations and blocks are recorded. Without this, a
     * paragraph or a program called only from such a place would look like it is referenced from
     * nowhere.
     *
     * <p>With {@code ownCall}, {@code node} is a CALL statement, and the EXEC blocks Che4z read
     * into its USING list are left to {@link #addStatement}, which maps them as statements.
     */
    private void collectNested(Node node, String procedureName, boolean ownCall) {
        for (Node child : node.getChildren()) {
            if (ownCall && holdsExecBlock(child)) {
                continue;
            }
            if (child instanceof PerformNode perform && !perform.isInline()) {
                performs.add(performRelation(procedureName, perform));
            } else if (child instanceof SubroutineNode call) {
                mapCall(call, callLocality(call));
            } else if (child instanceof ExecSqlNode) {
                mapEmbeddedSql(child);
            } else if (isExecBlock(child)) {
                mapEmbeddedCics(child);
            }
            collectNested(child, procedureName, false);
        }
    }

    /** The data item a GO TO ... DEPENDING ON names, empty for a GO TO without the phrase. */
    private static Optional<String> dependingOn(GoToNode goTo) {
        return goTo.getChildren().stream()
                .filter(QualifiedReferenceNode.class::isInstance)
                .flatMap(reference -> reference.getChildren().stream())
                .filter(VariableUsageNode.class::isInstance)
                .map(usage -> ((VariableUsageNode) usage).getName())
                .filter(name -> name != null && !name.isBlank())
                .findFirst();
    }

    /** The relation a procedure PERFORM makes, with the section each name is qualified by. */
    private PerformRelation performRelation(String procedureName, PerformNode perform) {
        Optional<ProcedureName> thru = Optional.ofNullable(perform.getThru());
        return new PerformRelation(procedureName, perform.getTarget().getName(),
                thru.map(ProcedureName::getName), rangeOf(perform.getLocality()),
                sectionOf(perform.getTarget()), thru.flatMap(SemanticModelMapper::sectionOf));
    }

    private static Optional<String> sectionOf(ProcedureName name) {
        return Optional.ofNullable(name.getInSection()).filter(section -> !section.isBlank());
    }

    /**
     * The section each target of a GO TO is qualified by. Che4z keeps the qualifier on the usage
     * node of each target, one per target in the order written; where the two lists do not line
     * up, every target is read unqualified.
     */
    private static List<Optional<String>> targetSections(GoToNode goTo) {
        List<Optional<String>> sections = goTo.getChildren().stream()
                .filter(CodeBlockUsageNode.class::isInstance)
                .map(usage -> Optional.ofNullable(((CodeBlockUsageNode) usage).getOfSection())
                        .filter(section -> !section.isBlank()))
                .toList();
        return sections.size() == goTo.getTargets().size() ? sections
                : Collections.nCopies(goTo.getTargets().size(), Optional.<String>empty());
    }

    private CompoundStatement mapIf(IfNode ifNode, String procedureName) {
        List<Node> condition = new ArrayList<>();
        List<Statement> thenStatements = new ArrayList<>();
        List<Statement> elseStatements = new ArrayList<>();
        boolean hasElse = false;
        for (Node child : inWrittenOrder(ifNode)) {
            if (child instanceof IfElseNode elseNode) {
                hasElse = true;
                for (Node elseChild : inWrittenOrder(elseNode)) {
                    if (isStatementNode(elseChild)) {
                        addStatement(elseChild, procedureName, elseStatements);
                    }
                }
            } else if (isStatementNode(child)) {
                addStatement(child, procedureName, thenStatements);
            } else if (thenStatements.isEmpty()) {
                condition.add(child);
            }
        }
        List<StatementBlock> blocks = new ArrayList<>();
        blocks.add(new StatementBlock("THEN", thenStatements));
        if (hasElse) {
            blocks.add(new StatementBlock("ELSE", elseStatements));
        }
        return new CompoundStatement(ControlKind.BRANCH, spanText(condition), blocks,
                rangeOf(ifNode.getLocality()));
    }

    private CompoundStatement mapEvaluate(EvaluateNode evaluateNode, String procedureName) {
        List<Node> selector = new ArrayList<>();
        List<StatementBlock> blocks = new ArrayList<>();
        String currentLabel = null;
        List<Statement> currentStatements = new ArrayList<>();
        for (Node child : inWrittenOrder(evaluateNode)) {
            if (child instanceof EvaluateWhenNode when) {
                if (currentLabel != null) {
                    blocks.add(new StatementBlock(currentLabel, currentStatements));
                }
                currentLabel = whenLabel(when);
                currentStatements = new ArrayList<>();
            } else if (child instanceof EvaluateWhenOtherNode other) {
                if (currentLabel != null) {
                    blocks.add(new StatementBlock(currentLabel, currentStatements));
                    currentLabel = null;
                }
                List<Statement> otherStatements = new ArrayList<>();
                for (Node otherChild : inWrittenOrder(other)) {
                    if (isStatementNode(otherChild)) {
                        addStatement(otherChild, procedureName, otherStatements);
                    }
                }
                blocks.add(new StatementBlock("OTHER", otherStatements));
            } else if (isStatementNode(child)) {
                if (currentLabel == null) {
                    // Assumes no statement appears before WHEN. If one does, keep it in an
                    // anonymous block instead of discarding it
                    currentLabel = "";
                }
                addStatement(child, procedureName, currentStatements);
            } else if (currentLabel == null && blocks.isEmpty()) {
                selector.add(child);
            }
        }
        if (currentLabel != null) {
            blocks.add(new StatementBlock(currentLabel, currentStatements));
        }
        if (blocks.isEmpty()) {
            blocks.add(new StatementBlock("", List.of()));
        }
        return new CompoundStatement(ControlKind.BRANCH, spanText(selector), blocks,
                rangeOf(evaluateNode.getLocality()));
    }

    private String whenLabel(EvaluateWhenNode when) {
        String text = texts.textOf(when.getLocality()).trim();
        if (text.toUpperCase(Locale.ROOT).startsWith("WHEN")) {
            text = text.substring(4).trim();
        }
        return text;
    }

    /**
     * An inline PERFORM as a loop. Its condition is the UNTIL phrase, or for a TIMES loop the
     * words before the body ({@code 3 TIMES}); a PERFORM with neither runs its body once, and its
     * condition is empty.
     */
    private CompoundStatement mapInlinePerform(PerformNode perform, String procedureName) {
        List<Statement> body = new ArrayList<>();
        String condition = null;
        for (Node child : inWrittenOrder(perform)) {
            if (child instanceof PerformUntilNode until) {
                condition = texts.textOf(until.getLocality()).trim();
            } else if (isStatementNode(child)) {
                addStatement(child, procedureName, body);
            }
        }
        if (condition == null) {
            // Che4z's node starts at the TIMES count, or at the first body statement when the
            // PERFORM writes no loop phrase.
            Locality locality = perform.getLocality();
            org.eclipse.lsp4j.Position end = body.isEmpty() ? null
                    : new org.eclipse.lsp4j.Position(body.get(0).range().start().line() - 1,
                            body.get(0).range().start().column() - 1);
            condition = locality == null || locality.getRange() == null || end == null
                    || !fileOf(locality.getUri()).equals(body.get(0).range().start().file())
                    ? "" : NestedBranches.withoutCommentLines(texts.extract(locality.getUri(),
                            new org.eclipse.lsp4j.Range(locality.getRange().getStart(), end)))
                            .replaceAll("\\s+", " ").trim();
        }
        return new CompoundStatement(ControlKind.LOOP, condition,
                List.of(new StatementBlock("", body)), rangeOf(perform.getLocality()));
    }

    /**
     * The EXEC CICS and EXEC SQL blocks Che4z read into the USING list of a CALL. Che4z's grammar
     * takes the placeholder an EXEC block leaves in the COBOL text as one more literal argument, so
     * a CALL with no period after it swallows the blocks that follow it. Each block is a statement
     * of its own, written after the CALL.
     */
    private static List<Node> absorbedExecBlocks(SubroutineNode call) {
        List<Node> blocks = new ArrayList<>();
        for (Node child : call.getChildren()) {
            if (holdsExecBlock(child)) {
                child.getDepthFirstStream().filter(SemanticModelMapper::isExecBlock)
                        .forEach(blocks::add);
            }
        }
        return blocks;
    }

    /** Whether a CALL argument is the placeholder of the EXEC blocks Che4z read into it. */
    private static boolean holdsExecBlock(Node node) {
        return node instanceof LiteralNode
                && node.getDepthFirstStream().anyMatch(SemanticModelMapper::isExecBlock);
    }

    private static boolean isExecBlock(Node node) {
        return node instanceof ExecSqlNode || node instanceof ExecCicsNode
                || node instanceof ExecCicsReturnNode || node instanceof ExecCicsHandleNode
                || node instanceof ExecCicsAbendNode;
    }

    /**
     * Where the CALL itself stands: its own locality, ended at its last word before the EXEC blocks
     * {@link #absorbedExecBlocks} gives back to the statements that follow.
     */
    private static Locality callLocality(SubroutineNode call) {
        Locality locality = call.getLocality();
        if (locality == null || locality.getRange() == null
                || absorbedExecBlocks(call).isEmpty()) {
            return locality;
        }
        org.eclipse.lsp4j.Position end = null;
        for (Node child : call.getChildren()) {
            Locality own = child.getLocality();
            if (holdsExecBlock(child) || own == null || own.getRange() == null
                    || !locality.getUri().equals(own.getUri())) {
                continue;
            }
            if (end == null || compare(own.getRange().getEnd(), end) > 0) {
                end = own.getRange().getEnd();
            }
        }
        return end == null ? locality : locality.toBuilder()
                .range(new org.eclipse.lsp4j.Range(locality.getRange().getStart(), end)).build();
    }

    private void mapCall(SubroutineNode call, Locality locality) {
        SubroutineNameNode nameNode = call.getDepthFirstStream()
                .filter(SubroutineNameNode.class::isInstance)
                .map(SubroutineNameNode.class::cast)
                .findFirst().orElse(null);
        if (nameNode != null && nameNode.getName() != null && !nameNode.getName().isBlank()) {
            // A literal specification becomes a SubroutineNameNode (static CALL)
            calls.add(new CallRelation(programId, CallKind.STATIC, nameNode.getName(),
                    rangeOf(locality)));
            return;
        }
        // For a variable specification (dynamic CALL), the first variable reference names the target
        call.getChildren().stream()
                .filter(QualifiedReferenceNode.class::isInstance)
                .flatMap(n -> n.getChildren().stream())
                .filter(VariableUsageNode.class::isInstance)
                .map(n -> ((VariableUsageNode) n).getName())
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .ifPresent(target -> calls.add(new CallRelation(programId, CallKind.DYNAMIC,
                        target, rangeOf(locality))));
    }

    private void mapEmbeddedSql(Node node) {
        String text = texts.textOf(node.getLocality());
        if (text.isBlank()) {
            return;
        }
        embeddedBlocks.add(new EmbeddedBlock(EmbeddedBlockKind.SQL, text, Map.of(),
                rangeOf(node.getLocality())));
    }

    private void mapEmbeddedCics(Node node) {
        String text = texts.textOf(node.getLocality());
        if (text.isBlank()) {
            return;
        }
        EmbeddedBlockKind kind = cicsKindOf(text);
        Map<String, String> operands = new HashMap<>();
        Matcher matcher = OPERAND_PATTERN.matcher(text);
        while (matcher.find()) {
            operands.putIfAbsent(matcher.group(1).toUpperCase(Locale.ROOT),
                    stripQuotes(matcher.group(2)));
        }
        embeddedBlocks.add(new EmbeddedBlock(kind, text, operands, rangeOf(node.getLocality())));
    }

    private static EmbeddedBlockKind cicsKindOf(String text) {
        String normalized = text.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).trim();
        int index = normalized.indexOf("EXEC CICS");
        String body = index < 0 ? normalized
                : normalized.substring(index + "EXEC CICS".length()).trim();
        if (body.startsWith("SEND MAP")) {
            return EmbeddedBlockKind.CICS_SEND_MAP;
        }
        if (body.startsWith("RECEIVE MAP")) {
            return EmbeddedBlockKind.CICS_RECEIVE_MAP;
        }
        if (body.startsWith("XCTL")) {
            return EmbeddedBlockKind.CICS_XCTL;
        }
        if (body.startsWith("LINK")) {
            return EmbeddedBlockKind.CICS_LINK;
        }
        if (body.startsWith("START")) {
            return EmbeddedBlockKind.CICS_START;
        }
        if (body.startsWith("RETURN")) {
            return body.contains("TRANSID") ? EmbeddedBlockKind.CICS_RETURN_TRANSID
                    : EmbeddedBlockKind.CICS_RETURN;
        }
        if (body.startsWith("HANDLE CONDITION")) {
            return EmbeddedBlockKind.CICS_HANDLE_CONDITION;
        }
        return EmbeddedBlockKind.CICS_OTHER;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("'") && value.endsWith("'"))
                        || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ---- Helpers ----

    private SimpleStatement simple(String verb, Node node) {
        return simple(verb, node.getLocality());
    }

    private SimpleStatement simple(String verb, Locality locality) {
        return new SimpleStatement(verb, texts.textOf(locality), rangeOf(locality));
    }

    /**
     * Determines a statement's verb. Since Che4z's AST represents many statements with a node
     * that carries no kind of its own, the token at the statement-start position is first looked
     * up from the CST; if the CST is unavailable, the original text's leading word is taken as
     * the verb. If neither is available, returns UNKNOWN.
     */
    private String resolveVerb(Node node) {
        Locality locality = node.getLocality();
        if (locality != null && locality.getRange() != null && cstCapture != null) {
            String verb = cstCapture.verbAt(locality.getUri(),
                    locality.getRange().getStart().getLine(),
                    locality.getRange().getStart().getCharacter());
            if (verb != null && !verb.isBlank()) {
                return verb;
            }
        }
        String text = texts.textOf(locality).trim();
        if (!text.isEmpty()) {
            return text.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        }
        return "UNKNOWN";
    }

    /**
     * Determines whether a child of a statement container (paragraph, IF branch, etc.) is a target
     * to map as a statement. A COPY statement written after a statement of the same sentence
     * leaves its CopyNode among the sentence's children; the statements it brings in are mapped
     * where they stand, and the node itself is none.
     */
    private static boolean isStatementNode(Node node) {
        return !(node instanceof QualifiedReferenceNode
                || node instanceof CopyNode
                || node instanceof VariableUsageNode
                || node instanceof VariableDefinitionNameNode
                || node instanceof LiteralNode
                || node instanceof PerformUntilNode
                || node instanceof CodeBlockUsageNode
                || node instanceof EvaluateWhenNode
                || node instanceof EvaluateWhenOtherNode
                || node instanceof IfElseNode
                || node instanceof SubroutineNameNode);
    }

    /**
     * Returns the original text covering the given nodes. Widening the start/end to the
     * minimum/maximum position lets syntax split across multiple nodes, such as a condition
     * expression, be extracted as a single piece of text. Nodes with a URI different from the
     * first node's (on the copybook side) are excluded from the range.
     */
    private String spanText(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return "";
        }
        String uri = nodes.get(0).getLocality().getUri();
        org.eclipse.lsp4j.Position start = nodes.get(0).getLocality().getRange().getStart();
        org.eclipse.lsp4j.Position end = nodes.get(0).getLocality().getRange().getEnd();
        for (Node node : nodes) {
            Locality locality = node.getLocality();
            if (locality == null || !uri.equals(locality.getUri())) {
                continue;
            }
            org.eclipse.lsp4j.Range range = locality.getRange();
            if (compare(range.getStart(), start) < 0) {
                start = range.getStart();
            }
            if (compare(range.getEnd(), end) > 0) {
                end = range.getEnd();
            }
        }
        return texts.extract(uri, new org.eclipse.lsp4j.Range(start, end)).trim();
    }

    private static int compare(org.eclipse.lsp4j.Position a, org.eclipse.lsp4j.Position b) {
        int lines = Integer.compare(a.getLine(), b.getLine());
        return lines != 0 ? lines : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    private static boolean isImplicit(Node node) {
        return node.getLocality() != null && UriPaths.isImplicit(node.getLocality().getUri());
    }

    private SourcePosition positionOf(Locality locality) {
        if (locality == null || locality.getRange() == null) {
            return SourcePosition.fileStart(sourceFilePath);
        }
        return new SourcePosition(fileOf(locality.getUri()),
                locality.getRange().getStart().getLine() + 1,
                locality.getRange().getStart().getCharacter() + 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    private SourceRange rangeOf(Locality locality) {
        if (locality == null || locality.getRange() == null) {
            SourcePosition start = SourcePosition.fileStart(sourceFilePath);
            return new SourceRange(start, start);
        }
        String file = fileOf(locality.getUri());
        SourcePosition start = new SourcePosition(file,
                locality.getRange().getStart().getLine() + 1,
                locality.getRange().getStart().getCharacter() + 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        SourcePosition end = new SourcePosition(file,
                locality.getRange().getEnd().getLine() + 1,
                locality.getRange().getEnd().getCharacter() + 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        // A node that ends inside a COPY member at the end of the program (a copybook holding
        // the common exit paragraphs) gets its end coordinates from the copybook while its URI
        // stays the program's: the end then precedes the start. Keep the start as the range.
        if (end.line() < start.line()
                || (end.line() == start.line() && end.column() < start.column())) {
            return new SourceRange(start, start);
        }
        return new SourceRange(start, end);
    }

    private String fileOf(String uri) {
        if (uri == null) {
            return sourceFilePath;
        }
        if (uri.equals(texts.mainUri())) {
            return sourceFilePath;
        }
        return UriPaths.toPathString(uri);
    }
}
