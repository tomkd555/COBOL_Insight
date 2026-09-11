package jp.cobolinsight.core.jcl;

/**
 * A PROC override the expansion had nowhere to put: the PROC has no step or no DD of that name, or
 * the PROC itself was never expanded, the member not being in the folder. {@code line} is the line
 * the override stands on and {@code text} the DD name or keyword as written.
 *
 * <p>The override itself is kept where it was written. An override of a PROC that was not expanded
 * stays a DD statement of the call step, named {@code procstep.ddname} as the author wrote it, so
 * the model still says what the job asked for; the miss says only that nothing took it.
 */
public record JclOverrideMiss(int line, String text) {

    public JclOverrideMiss {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
