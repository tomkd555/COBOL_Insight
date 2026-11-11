package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One FILE statement with the fields defined under it. The file name is the DD name the step has
 * to allocate, unless {@link #needsDd()} says the file lives nowhere a DD statement points at.
 *
 * @param name           the file name, upper case
 * @param organisation   how the file is accessed: {@code SEQUENTIAL} when the statement says
 *                       nothing, else {@code VSAM}, {@code ISAM}, {@code SQL}, {@code DLI},
 *                       {@code IDMS}, {@code VIRTUAL}, {@code PRINTER}, {@code PUNCH} or
 *                       {@code CARD}
 * @param recordFormat   {@code F}, {@code FB}, {@code V}, {@code VB}, {@code VBS} or {@code U}
 * @param recordLength   the record length the statement writes
 * @param table          whether the file is a table a SEARCH reads
 * @param instreamTable  whether the table's rows stand in the program itself
 * @param tableEntries   the largest number of rows the statement allows for, when it says
 * @param options        every word of the statement after the name, as written
 * @param fields         the fields of the record, in the order they are defined
 * @param tables         the Db2 tables an SQL file names
 */
public record EasytrieveFile(String name, String organisation, Optional<String> recordFormat,
        Optional<Integer> recordLength, boolean table, boolean instreamTable,
        Optional<Integer> tableEntries, List<String> options, List<EasytrieveField> fields,
        List<String> tables, SourcePosition position) {

    public EasytrieveFile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(organisation, "organisation");
        Objects.requireNonNull(recordFormat, "recordFormat");
        Objects.requireNonNull(recordLength, "recordLength");
        Objects.requireNonNull(tableEntries, "tableEntries");
        options = List.copyOf(options);
        fields = List.copyOf(fields);
        tables = List.copyOf(tables);
        Objects.requireNonNull(position, "position");
    }

    /**
     * Whether a step running the program has to allocate a DD of this name. A virtual file lives in
     * the product's own work file, an in-stream table in the program, and an SQL, DL/I or IDMS
     * file in a database.
     */
    public boolean needsDd() {
        return !instreamTable && switch (organisation) {
            case "VIRTUAL", "SQL", "DLI", "IDMS" -> false;
            default -> true;
        };
    }

    /** Whether the option word is written on the statement. */
    public boolean hasOption(String word) {
        return options.contains(word);
    }
}
