package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;

import java.util.List;
import java.util.Map;

/**
 * What {@code scan} produces: the summary the GUI reads, the call graph, the linker's record of how
 * it resolved each call, the inline COPY expansions, and what the walk dropped or reinterpreted.
 */
public record ScanOutcome(Summary summary, CallGraph callGraph, List<Finding> linkerFindings,
        CopyExpansions copyExpansions, List<String> warnings) {

    public ScanOutcome {
        linkerFindings = List.copyOf(linkerFindings);
        warnings = List.copyOf(warnings);
    }

    /** One file whose extension and content disagreed. Kind names are {@code AssetKind}'s own. */
    public record KindMismatch(String path, String byExtension, String byContent) {
    }

    public record Summary(List<String> analyzed, List<String> skipped, List<String> removed,
            int findingCount, int exitCode, boolean truncated, List<String> undecided,
            List<KindMismatch> mismatches, List<String> unreadable,
            Map<String, Integer> diagnostics) {

        /** A summary with nothing to report about the walk. */
        public Summary(List<String> analyzed, List<String> skipped, List<String> removed,
                int findingCount, int exitCode) {
            this(analyzed, skipped, removed, findingCount, exitCode, false,
                    List.of(), List.of(), List.of());
        }

        /** A summary from before the per-file diagnostic counts existed. */
        public Summary(List<String> analyzed, List<String> skipped, List<String> removed,
                int findingCount, int exitCode, boolean truncated, List<String> undecided,
                List<KindMismatch> mismatches, List<String> unreadable) {
            this(analyzed, skipped, removed, findingCount, exitCode, truncated, undecided,
                    mismatches, unreadable, Map.of());
        }

        /**
         * The summary JSON, with the SQLite project file it wrote. The GUI reads the asset list
         * from here. {@code copyExpansionFile} is where the COPY expansions were written, or null
         * when they were not (the key is then absent).
         *
         * <p>{@code undecided}, {@code mismatches} and {@code unreadable} are <b>complete</b> and
         * carry no separate count — the array length is the count. A count beside a sample leaves
         * room to truncate the sample, and the reader loses any way to know there is more.
         *
         * <p>{@code diagnostics} names each file this run read a statement of only in part, against
         * how many such places it found: a JCL statement the parser recovered from, a scheduler
         * directive it read past, and an embedded SQL statement the grammar would not read in full.
         * A file with none is absent, so the object is empty when everything was read whole.
         */
        public String toJson(String databaseFile, String copyExpansionFile) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writeArray(writer, "analyzed", analyzed);
            writeArray(writer, "skipped", skipped);
            writeArray(writer, "removed", removed);
            writer.name("findingCount").value(findingCount);
            writer.name("dbFile").value(databaseFile);
            if (copyExpansionFile != null) {
                writer.name("copyExpansionFile").value(copyExpansionFile);
            }
            if (truncated) {
                writer.name("truncated").value(true);
            }
            writeArray(writer, "undecided", undecided);
            writer.name("mismatches").beginArray();
            for (KindMismatch mismatch : mismatches) {
                writer.beginObject()
                        .name("path").value(mismatch.path())
                        .name("byExtension").value(mismatch.byExtension())
                        .name("byContent").value(mismatch.byContent())
                        .endObject();
            }
            writer.endArray();
            writeArray(writer, "unreadable", unreadable);
            writer.name("diagnostics").beginObject();
            for (Map.Entry<String, Integer> entry : diagnostics.entrySet()) {
                writer.name(entry.getKey()).value(entry.getValue());
            }
            writer.endObject();
            writer.name("exitCode").value(exitCode);
            writer.endObject();
            return writer.toString();
        }

        private static void writeArray(JsonWriter writer, String name, List<String> values) {
            writer.name(name).beginArray();
            for (String value : values) {
                writer.value(value);
            }
            writer.endArray();
        }
    }

    /**
     * The inline COPY expansions: for each program, the copybook lines that go where its COPY
     * statements sit, with their line of origin and the text after REPLACING. This is what shows
     * the original with its copybooks expanded in place.
     */
    public record CopyExpansions(List<ProgramExpansion> programs) {

        public CopyExpansions {
            programs = List.copyOf(programs);
        }

        /** One program's expansions. {@code relPath} is relative to the asset folder. */
        public record ProgramExpansion(String relPath, String programId,
                List<CopyInlineExpansion> expansions) {

            public ProgramExpansion {
                expansions = List.copyOf(expansions);
            }
        }

        public String toJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject().name("programs").beginArray();
            for (ProgramExpansion program : programs) {
                writer.beginObject()
                        .name("path").value(program.relPath())
                        .name("programId").value(program.programId())
                        .name("expansions").beginArray();
                for (CopyInlineExpansion expansion : program.expansions()) {
                    writer.beginObject()
                            .name("copyStatementLine").value(expansion.copyStatementLine())
                            .name("copybookName").value(expansion.copybookName())
                            .name("copybookPath").value(expansion.copybookPath())
                            .name("lines").beginArray();
                    for (ExpandedCopyLine line : expansion.lines()) {
                        writer.beginObject()
                                .name("copybookLine").value(line.copybookLine())
                                .name("text").value(line.text())
                                .endObject();
                    }
                    writer.endArray().endObject();
                }
                writer.endArray().endObject();
            }
            writer.endArray().endObject();
            return writer.toString();
        }
    }
}
