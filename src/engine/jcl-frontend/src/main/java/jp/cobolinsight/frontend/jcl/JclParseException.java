package jp.cobolinsight.frontend.jcl;

/** A JCL source that could not be understood. */
public class JclParseException extends RuntimeException {

    public JclParseException(String message) {
        super(message);
    }
}
