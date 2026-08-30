package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.source.LineRange;
import jp.cobolinsight.transpile.LayoutField;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A language-independent traversal that walks the data item tree ({@link DataItem}) and the layout
 * tree ({@link LayoutField}) in the same order, generating one 01 record into a class via
 * {@link LanguageEmitter}. The traversal itself is language-independent; the surface form is the
 * emitter's responsibility. Each generated line is recorded in the {@link LineTrackingEmitter} as a
 * declaration-line-to-generated-line correspondence (REDEFINES/groups/OCCURS are made visible as
 * 1:N / N:1 from the note and line count).
 */
public final class RecordClassGenerator {

    private RecordClassGenerator() {
    }

    public static void generate(String programId, String fileName, DataItem record,
            LayoutField layout, LanguageEmitter emitter, LineTrackingEmitter out) {
        emitter.emitFileHeader(out, programId, record.name());
        String className = Identifiers.sanitize(record.name());
        int start = out.nextLine();
        emitter.emitClassHeader(out, className, record.name(), layout.byteLength());
        out.addMapping(sourceId(record.position().file()), single(record.position().line()),
                fileName, start, out.lastLine(), "");

        Set<String> usedMembers = new HashSet<>();
        if (layout.pictureType().isPresent()) {
            emitLeaf(fileName, record, layout, List.of(), emitter, out, usedMembers);
        } else {
            walkChildren(fileName, record, layout, List.of(), emitter, out, usedMembers);
        }
        emitter.emitClassFooter(out);
    }

    private static void walkChildren(String fileName, DataItem group, LayoutField groupLayout,
            List<OccursDim> dims, LanguageEmitter emitter, LineTrackingEmitter out,
            Set<String> usedMembers) {
        List<DataItem> items = group.children();
        List<LayoutField> fields = groupLayout.children();
        for (int i = 0; i < items.size(); i++) {
            DataItem item = items.get(i);
            LayoutField field = fields.get(i);
            if (field.pictureType().isPresent()) {
                emitLeaf(fileName, item, field, dims, emitter, out, usedMembers);
            } else {
                out.blank();
                int start = out.nextLine();
                Optional<Integer> occurs =
                        field.occurs() > 1 ? Optional.of(field.occurs()) : Optional.empty();
                emitter.emitGroupComment(out, item.name(), field.offset(), field.byteLength(),
                        occurs, field.redefines());
                out.addMapping(sourceId(item.position().file()), single(item.position().line()),
                        fileName, start, out.lastLine(), groupNote(field));

                List<OccursDim> childDims = dims;
                if (field.occurs() > 1) {
                    childDims = new ArrayList<>(dims);
                    childDims.add(new OccursDim(field.occurs(), field.byteLength()));
                }
                walkChildren(fileName, item, field, childDims, emitter, out, usedMembers);
            }
        }
    }

    private static void emitLeaf(String fileName, DataItem item, LayoutField field,
            List<OccursDim> dims, LanguageEmitter emitter, LineTrackingEmitter out,
            Set<String> usedMembers) {
        PictureType pictureType = field.pictureType().orElseThrow();
        FieldKind kind = FieldKind.of(pictureType);
        String member = uniqueMember(item.name(), usedMembers);
        AccessorSpec spec = new AccessorSpec(item.name(), member, field.offset(), field.byteLength(),
                kind, pictureType.signed(), dims, item.position().line());
        out.blank();
        int start = out.nextLine();
        emitter.emitAccessor(out, spec);
        out.addMapping(sourceId(item.position().file()), single(item.position().line()), fileName,
                start, out.lastLine(), leafNote(field));

        for (ConditionName condition : field.conditionNames()) {
            String predicateMember = uniqueMember(condition.name(), usedMembers);
            ConditionSpec conditionSpec = new ConditionSpec(predicateMember, "get_" + member, kind,
                    condition.values(), dims, condition.position().line());
            out.blank();
            int conditionStart = out.nextLine();
            emitter.emitConditionPredicate(out, conditionSpec);
            out.addMapping(sourceId(condition.position().file()),
                    single(condition.position().line()), fileName, conditionStart, out.lastLine(),
                    "");
        }
    }

    private static String leafNote(LayoutField field) {
        return field.redefines().map(target -> "REDEFINES " + target).orElse("");
    }

    private static String groupNote(LayoutField field) {
        if (field.redefines().isPresent()) {
            return "REDEFINES " + field.redefines().get();
        }
        if (field.occurs() > 1) {
            return "OCCURS " + field.occurs();
        }
        return "";
    }

    /**
     * COBOL allows items with the same name to sit under different groups, so normalized names can
     * collide. Appends {@code _2}, {@code _3}, ... in traversal order to make them unique
     * (the order is fixed, so generation is deterministic).
     */
    private static String uniqueMember(String cobolName, Set<String> usedMembers) {
        String base = Identifiers.sanitize(cobolName);
        String candidate = base;
        int suffix = 2;
        while (!usedMembers.add(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static LineRange single(int line) {
        return new LineRange(line, line);
    }

    /** Returns the trailing file name of the path as the declaring source's identifier (distinguishes a copybook from the including program). */
    private static String sourceId(String file) {
        int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return separator >= 0 ? file.substring(separator + 1) : file;
    }
}
