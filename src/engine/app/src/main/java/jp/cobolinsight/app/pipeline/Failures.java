package jp.cobolinsight.app.pipeline;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.regex.Pattern;

/**
 * Maps an exception to the Japanese sentence fragment that describes it to the user. The engine's
 * stderr lines and JSON {@code error} fields reach the GUI unchanged, so no raw exception message
 * or stack trace is ever written to them; only this mapping's Japanese text is. An exception whose
 * message was authored in Japanese by this code base is passed through as it stands.
 */
public final class Failures {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");

    private Failures() {
    }

    /** The reason clause for one exception, without a trailing 「。」. */
    public static String describe(Throwable e) {
        String message = e.getMessage();
        if (message != null && JAPANESE.matcher(message).find()) {
            return message.endsWith("。") ? message.substring(0, message.length() - 1) : message;
        }
        if (e instanceof NoSuchFileException nsf) {
            return "ファイルが見つかりません: " + nsf.getFile();
        }
        if (e instanceof FileNotFoundException) {
            return "ファイルが見つかりません: " + message;
        }
        if (e instanceof AccessDeniedException ad) {
            return "アクセスが拒否されました: " + ad.getFile();
        }
        if (e instanceof UnsupportedCharsetException uc) {
            return "文字コード " + uc.getCharsetName() + " には対応していません";
        }
        if (e instanceof IllegalCharsetNameException ic) {
            return "文字コード " + ic.getCharsetName() + " には対応していません";
        }
        if (e instanceof UnsupportedEncodingException) {
            return "文字コード " + message + " には対応していません";
        }
        if (e instanceof MalformedInputException || e instanceof CharacterCodingException) {
            return "この文字コードでは復号できないバイトがあります";
        }
        // A wrapper such as PersistenceException says less than the exception it wraps.
        if (e.getCause() != null) {
            return describe(e.getCause());
        }
        if (e instanceof IOException) {
            return "入出力エラーです（" + e.getClass().getSimpleName() + "）";
        }
        return "予期しない内部エラーです（" + e.getClass().getSimpleName() + "）";
    }

    /** Whether choosing another code page could resolve the failure. */
    public static boolean codepageRelated(Throwable e) {
        if (e instanceof CharacterCodingException || e instanceof UnsupportedCharsetException
                || e instanceof IllegalCharsetNameException
                || e instanceof UnsupportedEncodingException) {
            return true;
        }
        return e.getCause() != null && codepageRelated(e.getCause());
    }
}
