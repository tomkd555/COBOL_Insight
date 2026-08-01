package jp.cobolinsight.encoding;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * 生バイト列を復号してUTF-16へ正規化し、原バイト列とオフセット表を保持した
 * {@link DecodedSource} を返す。手動指定のコードページは自動判別に優先する。
 */
public final class SourceDecoder {

    /** 復号後の先頭に現れるバイト順マーク(U+FEFF)。 */
    private static final char BYTE_ORDER_MARK = '﻿';

    private final CodePageDetector detector = new CodePageDetector();

    /** 自動判別で復号する。 */
    public DecodedSource decode(byte[] bytes) {
        DetectionResult detection = detector.detect(bytes);
        EncodingInfo info = new EncodingInfo(
                detection.codePage(), detection.confidence(), detection.soSiPresent(), false);
        return decodeWith(bytes, info);
    }

    /** 手動指定のコードページで復号する。自動判別の結果は用いない。 */
    public DecodedSource decode(byte[] bytes, CodePage codePage) {
        EncodingInfo info = new EncodingInfo(codePage, 100, CodePageDetector.containsSoSi(bytes), true);
        return decodeWith(bytes, info);
    }

    private static DecodedSource decodeWith(byte[] bytes, EncodingInfo info) {
        CharsetDecoder charsetDecoder = info.codePage().charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        // 1文字の産出には最低1バイトを要するため、産出される文字数はバイト数を超えない。
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        // 末尾に、文字位置 charCount(全バイト長を指す要素)の枠を1つ余分に取る。
        int[] charStarts = new int[bytes.length + 1];
        int pendingStart = 0;

        // 1バイトずつ入力を広げて復号し、産出された文字を未対応バイト群の先頭へ対応付ける。
        // SO/SIのように文字を産出せず消費されたバイトは対応付けから除く。
        if (bytes.length == 0) {
            // CharsetDecoder.flush は endOfInput=true の decode を済ませていないと呼べない。
            // 入力が空のときは下のループが1度も回らないため、ここで呼ぶ。
            charsetDecoder.decode(in, out, true);
        }
        for (int limit = 1; limit <= bytes.length; limit++) {
            in.limit(limit);
            int before = out.position();
            CoderResult result = charsetDecoder.decode(in, out, limit == bytes.length);
            requireNoError(result, info, limit);
            pendingStart = record(charStarts, out, before, pendingStart, in.position());
        }
        int before = out.position();
        requireNoError(charsetDecoder.flush(out), info, bytes.length);
        record(charStarts, out, before, pendingStart, bytes.length);

        // バイト順マークは符号化の印であって本文の文字ではない。本文に残すと1行目の桁が1つずれ、
        // 固定形式の一連番号領域・標識領域の桁がすべてずれる。原バイト列とオフセット表は保つ。
        String decoded = new String(out.array(), 0, out.position());
        int from = decoded.isEmpty() || decoded.charAt(0) != BYTE_ORDER_MARK ? 0 : 1;
        String text = decoded.substring(from);
        int[] table = Arrays.copyOfRange(charStarts, from, from + text.length() + 1);
        table[text.length()] = bytes.length;
        return new DecodedSource(text, bytes, new ByteOffsetTable(text, table), info);
    }

    private static int record(int[] charStarts, CharBuffer out, int before, int pendingStart, int consumedTo) {
        for (int i = before; i < out.position(); i++) {
            charStarts[i] = pendingStart;
        }
        return Math.max(pendingStart, consumedTo);
    }

    private static void requireNoError(CoderResult result, EncodingInfo info, int byteOffset) {
        if (result.isError()) {
            throw new IllegalArgumentException(
                    "コードページ " + info.codePage().charsetName() + " で復号できないバイト列がある(オフセット "
                            + byteOffset + " 付近)");
        }
    }
}
