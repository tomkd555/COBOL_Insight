package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One activity of a program: a JOB, a SORT, a PROGRAM or a SCREEN, with the statements, procedures
 * and reports written under it.
 *
 * @param kind       {@code JOB}, {@code SORT}, {@code PROGRAM} or {@code SCREEN}
 * @param header     the statement that opens the activity
 * @param input      what a JOB reads automatically: the file names of its INPUT, {@code NULL} for
 *                   a job that reads nothing on its own, {@code SQL} for one a cursor drives; empty
 *                   where the statement names no INPUT, and for the other kinds
 * @param statements the statements of the activity itself, nested ones included, in order
 */
public record EasytrieveActivity(String kind, EasytrieveStatement header, List<String> input,
        Optional<String> name, List<EasytrieveStatement> statements,
        List<EasytrieveProcedure> procedures, List<EasytrieveReport> reports,
        SourcePosition position) {

    public EasytrieveActivity {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Objects.requireNonNull(header, "header");
        input = List.copyOf(input);
        Objects.requireNonNull(name, "name");
        statements = List.copyOf(statements);
        procedures = List.copyOf(procedures);
        reports = List.copyOf(reports);
        Objects.requireNonNull(position, "position");
    }

    /** Whether this is a JOB that reads no file on its own and so runs until it is told to stop. */
    public boolean isNullInputJob() {
        return kind.equals("JOB") && input.size() == 1 && input.get(0).equals("NULL");
    }

    /**
     * Every statement that can run under the activity: its own, those of its procedures and those
     * of the procedures of its reports. Report definition statements are not among them.
     */
    public List<EasytrieveStatement> executableStatements() {
        List<EasytrieveStatement> all = new ArrayList<>(statements);
        procedures.forEach(procedure -> all.addAll(procedure.statements()));
        for (EasytrieveReport report : reports) {
            report.procedures().forEach(procedure -> all.addAll(procedure.statements()));
        }
        return all;
    }
}
