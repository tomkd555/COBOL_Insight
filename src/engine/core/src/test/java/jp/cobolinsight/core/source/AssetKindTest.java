package jp.cobolinsight.core.source;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the canonical extension table ({@link AssetKind}). */
class AssetKindTest {

    @Test
    void everyKindDeclaresAtLeastOneLowercaseDottedExtension() {
        for (AssetKind kind : AssetKind.values()) {
            assertTrue(!kind.extensions().isEmpty(), kind + " は拡張子を持つこと");
            for (String extension : kind.extensions()) {
                assertTrue(extension.startsWith("."), extension + " は先頭がドットであること");
                assertEquals(extension.toLowerCase(java.util.Locale.ROOT), extension,
                        extension + " は小文字であること");
            }
        }
    }

    @Test
    void extensionsAreUniqueAcrossKinds() {
        List<String> all = new ArrayList<>();
        for (AssetKind kind : AssetKind.values()) {
            all.addAll(kind.extensions());
        }
        Set<String> unique = new HashSet<>(all);
        assertEquals(all.size(), unique.size(), "拡張子が2つの種別へ跨がらないこと: " + all);
    }

    @Test
    void ofExtensionAcceptsDottedAndBareFormsIgnoringCase() {
        assertEquals(AssetKind.COBOL, AssetKind.ofExtension(".cbl"));
        assertEquals(AssetKind.COBOL, AssetKind.ofExtension("CBL"));
        assertEquals(AssetKind.COBOL, AssetKind.ofExtension(".CoBoL"));
        assertEquals(AssetKind.COPYBOOK, AssetKind.ofExtension(".copy"));
        assertEquals(AssetKind.JCL, AssetKind.ofExtension("jcl"));
        assertEquals(AssetKind.BMS, AssetKind.ofExtension(".bms"));
    }

    @Test
    void ofExtensionReturnsNullForUnknownAndEmpty() {
        assertNull(AssetKind.ofExtension(".txt"));
        assertNull(AssetKind.ofExtension(""));
        assertNull(AssetKind.ofExtension(null));
    }

    @Test
    void ofFileNameReadsTheLastExtensionOfTheLastPathSegment() {
        assertEquals(AssetKind.COPYBOOK, AssetKind.ofFileName("copybook/SYKCPY1.CPY"));
        assertEquals(AssetKind.COBOL, AssetKind.ofFileName("C:\\assets\\SYK001.cbl"));
        assertEquals(AssetKind.COBOL, AssetKind.ofFileName("SYK001.bak.cob"));
        assertNull(AssetKind.ofFileName("README"));
        assertNull(AssetKind.ofFileName(".gitignore"), "先頭のドットは拡張子ではないこと");
        assertNull(AssetKind.ofFileName("dir.cbl/README"),
                "拡張子はディレクトリ名ではなくファイル名から採ること");
    }

    @Test
    void extensionOfNormalizesToLowercaseWithLeadingDot() {
        assertEquals(".cbl", AssetKind.extensionOf("SYK001.CBL"));
        assertEquals("", AssetKind.extensionOf("Makefile"));
        assertEquals("", AssetKind.extensionOf(".gitignore"));
    }
}
