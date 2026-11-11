package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.ims.ImsDbd;
import jp.cobolinsight.core.ims.ImsPsb;

import java.util.List;

/**
 * The parsed assets that are neither COBOL, JCL, SQL nor BMS: Easytrieve programs — the members of
 * the asset folder and the in-stream programs of the JCL alike — and the IMS database and PSB
 * generations. Handed to the rules as an artefact of the analysis context, which is what
 * {@link AnalysisContext#easytrievePrograms()}, {@link AnalysisContext#imsDbds()} and
 * {@link AnalysisContext#imsPsbs()} read.
 */
public record MiddlewareAssets(List<EasytrieveProgram> easytrievePrograms, List<ImsDbd> imsDbds,
        List<ImsPsb> imsPsbs) {

    public static final MiddlewareAssets NONE =
            new MiddlewareAssets(List.of(), List.of(), List.of());

    public MiddlewareAssets {
        easytrievePrograms = List.copyOf(easytrievePrograms);
        imsDbds = List.copyOf(imsDbds);
        imsPsbs = List.copyOf(imsPsbs);
    }
}
