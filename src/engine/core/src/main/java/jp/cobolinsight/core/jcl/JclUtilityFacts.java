package jp.cobolinsight.core.jcl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a step's control cards say the step does, read out of the in-stream data the utility takes
 * as input. A step running an ordinary program carries nothing but {@code ddRoles}, which the DISP
 * of each DD alone decides; a utility step carries what its own cards state on top.
 *
 * @param programRuns the programs the step's cards run, the DSN {@code RUN PROGRAM} form included
 * @param binds the BIND and REBIND requests the cards make
 * @param datasetUses data sets the cards name directly, without a DD statement
 * @param tableUses Db2 tables the cards name
 * @param ddRoles how the step uses each of its DD statements, by DD name
 */
public record JclUtilityFacts(List<ProgramRun> programRuns, List<BindRequest> binds,
        List<DatasetUse> datasetUses, List<TableUse> tableUses,
        Map<String, DatasetAccess> ddRoles) {

    /** How a step uses a data set, a table or a DD. */
    public enum DatasetAccess {
        READ, WRITE, UPDATE, CREATE, DELETE, UNKNOWN
    }

    /** One program the step's cards run, with the plan, parameters and library named with it. */
    public record ProgramRun(String program, Optional<String> plan, Optional<String> parms,
            Optional<String> library) {

        public ProgramRun {
            if (program == null || program.isBlank()) {
                throw new IllegalArgumentException("program must not be blank");
            }
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(parms, "parms");
            Objects.requireNonNull(library, "library");
        }
    }

    /**
     * One BIND or REBIND request. {@code kind} is {@code PLAN}, {@code PACKAGE},
     * {@code REBIND-PLAN} or {@code REBIND-PACKAGE}; {@code members} the DBRM or package members
     * named, and {@code options} every other keyword of the card.
     */
    public record BindRequest(String kind, String name, List<String> members,
            Map<String, String> options) {

        public BindRequest {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("kind must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            members = List.copyOf(members);
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
    }

    /** One data set a card names directly, without going through a DD statement. */
    public record DatasetUse(String dataset, DatasetAccess access) {

        public DatasetUse {
            if (dataset == null || dataset.isBlank()) {
                throw new IllegalArgumentException("dataset must not be blank");
            }
            Objects.requireNonNull(access, "access");
        }
    }

    /** One Db2 table a card names. */
    public record TableUse(String table, DatasetAccess access) {

        public TableUse {
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("table must not be blank");
            }
            Objects.requireNonNull(access, "access");
        }
    }

    // Every map of this package is copied into a LinkedHashMap rather than through Map.copyOf,
    // whose iteration order varies from one run to the next; these maps are read, and written to
    // the model snapshots, in the order the source states them.
    public JclUtilityFacts {
        programRuns = List.copyOf(programRuns);
        binds = List.copyOf(binds);
        datasetUses = List.copyOf(datasetUses);
        tableUses = List.copyOf(tableUses);
        ddRoles = Collections.unmodifiableMap(new LinkedHashMap<>(ddRoles));
    }

    /** Whether the step's cards said nothing at all, DD roles included. */
    public boolean isEmpty() {
        return programRuns.isEmpty() && binds.isEmpty() && datasetUses.isEmpty()
                && tableUses.isEmpty() && ddRoles.isEmpty();
    }
}
