package jp.cobolinsight.core.jcl;

/**
 * A referback no step of the job answers: {@code *.step.dd} naming a step or a DD that is not
 * there, or one written before the DD it points at. {@code line} is the line the DD stands on and
 * {@code text} the referback as written.
 */
public record JclReferbackMiss(int line, String text) {

    public JclReferbackMiss {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
