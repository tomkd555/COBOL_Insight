package jp.cobolinsight.core.ims;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An IMS program specification block as its PSBGEN source states it: the PCBs in the order the
 * program receives them, and for each database PCB the segments the program is sensitive to.
 */
public record ImsPsb(String name, String language, List<Pcb> pcbs, String sourceFile,
        SourcePosition position) {

    /**
     * One PCB statement. {@code type} is {@code DB}, {@code TP} or {@code GSAM}; {@code dbdName}
     * is the database a DB or GSAM PCB names, and {@code procopt} its processing options.
     */
    public record Pcb(String type, Optional<String> dbdName, Optional<String> procopt,
            List<Senseg> segments, SourcePosition position) {

        public Pcb {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(dbdName, "dbdName");
            Objects.requireNonNull(procopt, "procopt");
            segments = List.copyOf(segments);
            Objects.requireNonNull(position, "position");
        }

        /** Whether the processing options let the program insert, replace or delete. */
        public boolean updates() {
            String options = procopt.orElse("");
            return options.contains("A") || options.contains("I") || options.contains("R")
                    || options.contains("D") || options.contains("L");
        }
    }

    /** One SENSEG statement. */
    public record Senseg(String name, Optional<String> parent, Optional<String> procopt,
            SourcePosition position) {

        public Senseg {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(procopt, "procopt");
            Objects.requireNonNull(position, "position");
        }
    }

    public ImsPsb {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(language, "language");
        pcbs = List.copyOf(pcbs);
        Objects.requireNonNull(position, "position");
    }
}
