package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One Easytrieve Plus program: a source member of its own, or the program a JCL step hands the
 * compiler in-stream.
 *
 * <p>{@code name} is the member name for a source file, which is the name a load module built from
 * it is executed under, and {@code job.step} for an in-stream program, whose {@code host} names the
 * step. {@code fileAccess} says what the program does with each file it declares — {@code READ},
 * {@code WRITE} or {@code UPDATE} — and holds no entry for a file no statement uses.
 *
 * <p>A macro the program invokes is expanded where it is invoked, so the files and fields a macro
 * defines are here like any other; {@code macros} records each invocation and whether its member
 * was found, and a statement that came from a macro stands in the macro's own file.
 */
public record EasytrieveProgram(String name, String sourceFile, boolean macroMember,
        Optional<Host> host, List<EasytrieveStatement> parms, List<EasytrieveFile> files,
        List<EasytrieveField> workingFields, List<EasytrieveActivity> activities,
        List<SqlBlock> sql, List<Call> calls, List<MacroUse> macros,
        Map<String, String> fileAccess, boolean catalogFields, SourcePosition position) {

    /** The JCL step an in-stream program is compiled and run by. */
    public record Host(String jobName, String stepName) {
    }

    /** One SQL statement of the program, without the {@code SQL} keyword in front of it. */
    public record SqlBlock(String text, SourceRange range) {
    }

    /** A CALL, LINK or TRANSFER. {@code literal} is false where a field names the target. */
    public record Call(String kind, String target, boolean literal, SourcePosition position) {
    }

    /** One {@code %NAME} invocation. */
    public record MacroUse(String name, boolean resolved, SourcePosition position) {
    }

    public EasytrieveProgram {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        Objects.requireNonNull(host, "host");
        parms = List.copyOf(parms);
        files = List.copyOf(files);
        workingFields = List.copyOf(workingFields);
        activities = List.copyOf(activities);
        sql = List.copyOf(sql);
        calls = List.copyOf(calls);
        macros = List.copyOf(macros);
        fileAccess = Collections.unmodifiableMap(new LinkedHashMap<>(fileAccess));
        Objects.requireNonNull(position, "position");
    }

    /** Whether the program stands in a JCL step rather than in a member of its own. */
    public boolean inStream() {
        return host.isPresent();
    }

    public Optional<EasytrieveFile> file(String fileName) {
        String wanted = fileName.toUpperCase(Locale.ROOT);
        return files.stream().filter(file -> file.name().equals(wanted)).findFirst();
    }

    /** Every field of the program: those of each file, then working storage. */
    public List<EasytrieveField> fields() {
        List<EasytrieveField> all = new ArrayList<>();
        files.forEach(file -> all.addAll(file.fields()));
        all.addAll(workingFields);
        return all;
    }

    /** Every statement that can run, activity by activity in source order. */
    public List<EasytrieveStatement> executableStatements() {
        List<EasytrieveStatement> all = new ArrayList<>();
        activities.forEach(activity -> all.addAll(activity.executableStatements()));
        return all;
    }
}
