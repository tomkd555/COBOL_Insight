package jp.cobolinsight.jclfrontend;

/** JCL のパース失敗を表す。 */
public class JclParseException extends RuntimeException {

	public JclParseException(String message) {
		super(message);
	}

	public JclParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
