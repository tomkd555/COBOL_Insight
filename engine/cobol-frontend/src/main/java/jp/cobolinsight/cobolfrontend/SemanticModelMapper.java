package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.semantic.CallKind;
import jp.cobolinsight.engineapi.semantic.CallRelation;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.ConditionName;
import jp.cobolinsight.engineapi.semantic.ControlKind;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.Occurs;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp.cobol.common.model.tree.CodeBlockUsageNode;
import org.eclipse.lsp.cobol.common.model.tree.DivisionNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateWhenNode;
import org.eclipse.lsp.cobol.common.model.tree.EvaluateWhenOtherNode;
import org.eclipse.lsp.cobol.common.model.tree.ExitNode;
import org.eclipse.lsp.cobol.common.model.tree.GoBackNode;
import org.eclipse.lsp.cobol.common.model.tree.GoToNode;
import org.eclipse.lsp.cobol.common.model.tree.IfElseNode;
import org.eclipse.lsp.cobol.common.model.tree.IfNode;
import org.eclipse.lsp.cobol.common.model.tree.LiteralNode;
import org.eclipse.lsp.cobol.common.model.tree.Node;
import org.eclipse.lsp.cobol.common.model.tree.ParagraphNode;
import org.eclipse.lsp.cobol.common.model.tree.PerformNode;
import org.eclipse.lsp.cobol.common.model.tree.PerformUntilNode;
import org.eclipse.lsp.cobol.common.model.tree.ProcedureSectionNode;
import org.eclipse.lsp.cobol.common.model.tree.ProgramNode;
import org.eclipse.lsp.cobol.common.model.tree.SentenceNode;
import org.eclipse.lsp.cobol.common.model.tree.StopNode;
import org.eclipse.lsp.cobol.common.model.tree.SubroutineNameNode;
import org.eclipse.lsp.cobol.common.model.tree.SubroutineNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.ElementaryNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.MultiTableDataNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.OccursClause;
import org.eclipse.lsp.cobol.common.model.tree.variable.QualifiedReferenceNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.TableDataNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.UsageFormat;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableDefinitionNameNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableUsageNode;
import org.eclipse.lsp.cobol.common.model.tree.variable.VariableWithLevelNode;
import org.eclipse.lsp.cobol.common.model.tree.variables.ConditionDataNameNode;
import org.eclipse.lsp.cobol.common.model.variables.DivisionType;
import org.eclipse.lsp.cobol.implicitDialects.cics.nodes.ExecCicsNode;
import org.eclipse.lsp.cobol.implicitDialects.cics.nodes.ExecCicsReturnNode;
import org.eclipse.lsp.cobol.implicitDialects.sql.node.ExecSqlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Che4z の AST(+CST 補完)を engine-api の正規化意味モデルへ写像する。 */
final class SemanticModelMapper {

    private static final Pattern OPERAND_PATTERN =
            Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*\\(\\s*([^()]*?)\\s*\\)");

    private final String sourceFilePath;
    private final SourceTexts texts;
    private final CstCapture cstCapture;

    private final List<CallRelation> calls = new ArrayList<>();
    private final List<PerformRelation> performs = new ArrayList<>();
    private final List<EmbeddedBlock> embeddedBlocks = new ArrayList<>();
    private String programId;

    SemanticModelMapper(String sourceFilePath, SourceTexts texts, CstCapture cstCapture) {
        this.sourceFilePath = sourceFilePath;
        this.texts = texts;
        this.cstCapture = cstCapture;
    }

    CobolSemanticModel map(ProgramNode program) {
        programId = program.getProgramName();
        List<DataItem> dataItems = mapDataDivision(program);
        List<Procedure> procedures = mapProcedureDivision(program);
        return new CobolSemanticModel(programId, sourceFilePath, dataItems, procedures, calls,
                performs, embeddedBlocks,
                cstCapture == null ? List.of() : cstCapture.copyExpansions());
    }

    // ---- データ部 ----

    private List<DataItem> mapDataDivision(ProgramNode program) {
        List<DataItem> items = new ArrayList<>();
        for (Node child : program.getChildren()) {
            if (child instanceof DivisionNode division
                    && division.getDivisionType() == DivisionType.DATA_DIVISION) {
                collectDataItems(division, items);
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
        return new DataItem(variable.getLevel(), variable.getName(), picture, usage, redefines,
                occurs, conditionNames, children, positionOf(variable.getLocality()));
    }

    private static boolean isMappableLevel(int level) {
        return (level >= 1 && level <= 49) || level == 66 || level == 77;
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

    // ---- 手続き部 ----

    private List<Procedure> mapProcedureDivision(ProgramNode program) {
        List<Procedure> procedures = new ArrayList<>();
        collectProcedures(program, procedures, Optional.empty());
        return procedures;
    }

    private void collectProcedures(Node parent, List<Procedure> out, Optional<String> section) {
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
        List<Statement> statements = new ArrayList<>();
        for (Node child : block.getChildren()) {
            if (child instanceof SentenceNode sentence) {
                for (Node statementNode : sentence.getChildren()) {
                    if (isStatementNode(statementNode)) {
                        statements.add(mapStatement(statementNode, name));
                    }
                }
            }
        }
        return new Procedure(name, kind, section, statements, rangeOf(block.getLocality()));
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
            performs.add(new PerformRelation(procedureName, perform.getTarget().getName(),
                    Optional.ofNullable(perform.getThru()).map(t -> t.getName()),
                    rangeOf(perform.getLocality())));
            return simple("PERFORM", node);
        }
        if (node instanceof GoToNode goTo) {
            return new GoToStatement(goTo.getTargets(), Optional.empty(),
                    rangeOf(goTo.getLocality()));
        }
        if (node instanceof SubroutineNode call) {
            mapCall(call);
            return simple("CALL", node);
        }
        if (node instanceof ExecSqlNode) {
            mapEmbeddedSql(node);
            return simple("EXEC SQL", node);
        }
        if (node instanceof ExecCicsNode || node instanceof ExecCicsReturnNode) {
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
        return simple(resolveVerb(node), node);
    }

    private CompoundStatement mapIf(IfNode ifNode, String procedureName) {
        List<Node> condition = new ArrayList<>();
        List<Statement> thenStatements = new ArrayList<>();
        List<Statement> elseStatements = new ArrayList<>();
        boolean hasElse = false;
        for (Node child : ifNode.getChildren()) {
            if (child instanceof IfElseNode elseNode) {
                hasElse = true;
                for (Node elseChild : elseNode.getChildren()) {
                    if (isStatementNode(elseChild)) {
                        elseStatements.add(mapStatement(elseChild, procedureName));
                    }
                }
            } else if (isStatementNode(child)) {
                thenStatements.add(mapStatement(child, procedureName));
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
        for (Node child : evaluateNode.getChildren()) {
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
                for (Node otherChild : other.getChildren()) {
                    if (isStatementNode(otherChild)) {
                        otherStatements.add(mapStatement(otherChild, procedureName));
                    }
                }
                blocks.add(new StatementBlock("OTHER", otherStatements));
            } else if (isStatementNode(child)) {
                if (currentLabel == null) {
                    // WHEN より前に文は現れない前提。現れた場合は捨てずに匿名ブロックへ入れる
                    currentLabel = "";
                }
                currentStatements.add(mapStatement(child, procedureName));
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

    private CompoundStatement mapInlinePerform(PerformNode perform, String procedureName) {
        List<Statement> body = new ArrayList<>();
        String condition = "";
        for (Node child : perform.getChildren()) {
            if (child instanceof PerformUntilNode until) {
                condition = texts.textOf(until.getLocality()).trim();
            } else if (isStatementNode(child)) {
                body.add(mapStatement(child, procedureName));
            }
        }
        return new CompoundStatement(ControlKind.LOOP, condition,
                List.of(new StatementBlock("", body)), rangeOf(perform.getLocality()));
    }

    private void mapCall(SubroutineNode call) {
        SubroutineNameNode nameNode = call.getDepthFirstStream()
                .filter(SubroutineNameNode.class::isInstance)
                .map(SubroutineNameNode.class::cast)
                .findFirst().orElse(null);
        if (nameNode != null && nameNode.getName() != null && !nameNode.getName().isBlank()) {
            // リテラル指定は SubroutineNameNode になる(静的 CALL)
            calls.add(new CallRelation(programId, CallKind.STATIC, nameNode.getName(),
                    rangeOf(call.getLocality())));
            return;
        }
        // 変数指定(動的 CALL)は先頭の変数参照が呼出し先を表す
        call.getChildren().stream()
                .filter(QualifiedReferenceNode.class::isInstance)
                .flatMap(n -> n.getChildren().stream())
                .filter(VariableUsageNode.class::isInstance)
                .map(n -> ((VariableUsageNode) n).getName())
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .ifPresent(target -> calls.add(new CallRelation(programId, CallKind.DYNAMIC,
                        target, rangeOf(call.getLocality()))));
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
        if (kind == null) {
            return;
        }
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
        if (body.startsWith("RETURN") && body.contains("TRANSID")) {
            return EmbeddedBlockKind.CICS_RETURN_TRANSID;
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("'") && value.endsWith("'"))
                        || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ---- 補助 ----

    private SimpleStatement simple(String verb, Node node) {
        String text = texts.textOf(node.getLocality());
        return new SimpleStatement(verb, text, rangeOf(node.getLocality()));
    }

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

    /** 文の入れ物(段落・IF 分岐など)の子のうち、文として写像する対象かを判定する。 */
    private static boolean isStatementNode(Node node) {
        return !(node instanceof QualifiedReferenceNode
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
