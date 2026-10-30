package jp.cobolinsight.frontend.cobol;

import org.eclipse.lsp.cobol.common.dialects.CobolLanguageId;
import org.eclipse.lsp.cobol.common.pipeline.Pipeline;
import org.eclipse.lsp.cobol.common.pipeline.Stage;
import org.eclipse.lsp.cobol.dialects.TrueDialectServiceImpl;
import org.eclipse.lsp.cobol.dialects.ibm.ParserStage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The COBOL pipeline the engine runs is the vendor's IBM pipeline stage for stage, its parser
 * stage wrapped: a Che4z upgrade that adds, drops or reorders a stage, or builds one from other
 * parts, fails here.
 */
class CapturingDialectsTest {

    @Test
    void theStagesAreTheVendorsInItsOrder() throws ReflectiveOperationException {
        List<Stage<?, ?, ?>> ours = stages(capturing().getPipeline(CobolLanguageId.COBOL));
        List<Stage<?, ?, ?>> theirs = stages(vendor().getPipeline(CobolLanguageId.COBOL));

        assertTrue(ours.size() > 1, ours::toString);
        assertEquals(theirs.stream().map(Stage::getName).toList(),
                ours.stream().map(Stage::getName).toList());
    }

    /**
     * Each stage is of the vendor's class and holds parts of the classes the vendor's stage
     * holds, field by field, the preprocessor of the cleanup and dialect stages included. The
     * parser stage is wrapped, so it is the vendor's ParserStage the wrapper runs.
     */
    @Test
    void eachStageIsBuiltFromWhatTheVendorsIs() throws ReflectiveOperationException {
        List<Stage<?, ?, ?>> ours = stages(capturing().getPipeline(CobolLanguageId.COBOL));
        List<Stage<?, ?, ?>> theirs = stages(vendor().getPipeline(CobolLanguageId.COBOL));
        int wrapped = 0;
        for (int i = 0; i < theirs.size(); i++) {
            Stage<?, ?, ?> vendorStage = theirs.get(i);
            Stage<?, ?, ?> ourStage = ours.get(i);
            if (vendorStage instanceof ParserStage) {
                wrapped++;
                assertTrue(capturedFieldsOf(ourStage).contains(ParserStage.class.getName()),
                        () -> "the wrapper runs a ParserStage: " + ourStage.getClass());
                continue;
            }
            assertEquals(vendorStage.getClass(), ourStage.getClass());
            assertEquals(partsOf(vendorStage), partsOf(ourStage), vendorStage.getName());
        }
        assertEquals(1, wrapped);
    }

    private Che4zRuntime.CapturingDialects capturing() throws ReflectiveOperationException {
        return (Che4zRuntime.CapturingDialects) field(Che4zRuntime.instance(), "dialects");
    }

    private TrueDialectServiceImpl vendor() throws ReflectiveOperationException {
        return (TrueDialectServiceImpl) field(capturing(), "dialects");
    }

    /** The class of what each instance field of a stage holds, in declaration order. */
    private static List<String> partsOf(Object stage) throws ReflectiveOperationException {
        List<String> parts = new ArrayList<>();
        for (Field field : stage.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(stage);
            parts.add(field.getName() + "=" + (value == null ? "null" : value.getClass().getName()));
        }
        return parts;
    }

    /** The classes of what the fields of an anonymous wrapper hold, captured variables included. */
    private static List<String> capturedFieldsOf(Object stage) throws ReflectiveOperationException {
        List<String> classes = new ArrayList<>();
        for (Field field : stage.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(stage);
            if (value != null) {
                classes.add(value.getClass().getName());
            }
        }
        return classes;
    }

    @SuppressWarnings("unchecked")
    private static List<Stage<?, ?, ?>> stages(Pipeline<?> pipeline)
            throws ReflectiveOperationException {
        return (List<Stage<?, ?, ?>>) field(pipeline, "stages");
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
