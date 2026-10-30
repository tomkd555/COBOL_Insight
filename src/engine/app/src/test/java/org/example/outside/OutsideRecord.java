package org.example.outside;

/**
 * A record and an enum outside the engine's package, shaped so that the parse cache's codec would
 * rebuild them from bytes but for its package check (RecordCodecTest).
 */
public record OutsideRecord(String text) {

    /** An enum with the constant the test's bytes name. */
    public enum Kind {
        A
    }
}
