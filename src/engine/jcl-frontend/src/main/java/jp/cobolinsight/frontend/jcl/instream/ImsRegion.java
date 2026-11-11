package jp.cobolinsight.frontend.jcl.instream;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;

/**
 * The application an IMS region controller runs. {@code DFSRRC00} is the EXEC target of every IMS
 * batch and BMP step, and its PARM names the region type, the application program and the PSB:
 * {@code PARM='DLI,ORDUPD01,ORDPSB01'}, {@code PARM=(BMP,ORDUPD01,ORDPSB01,…)}. The program is
 * what the step is for; the controller is only the way in.
 *
 * <p>A PSB left out is the program's own name, which is how IMS reads the PARM. The utility region
 * types ({@code ULU}, {@code UDR}) run an IMS utility against a DBD rather than an application
 * against a PSB, so they yield nothing here.
 */
final class ImsRegion {

    private static final Set<String> CONTROLLERS = Set.of("DFSRRC00", "DFSPCC20");

    /** The region types whose second and third positional values are a program and its PSB. */
    private static final Set<String> APPLICATION_REGIONS = Set.of("DLI", "DBB", "BMP", "IFP");

    private ImsRegion() {
    }

    static boolean isRegionController(String program) {
        return CONTROLLERS.contains(program);
    }

    static Optional<ProgramRun> read(JclStep step) {
        String parm = step.parm().orElse("").strip();
        if (parm.startsWith("(") && parm.endsWith(")")) {
            parm = parm.substring(1, parm.length() - 1);
        }
        String[] values = parm.replace("'", "").split(",", -1);
        if (values.length < 2) {
            return Optional.empty();
        }
        String region = values[0].strip().toUpperCase(Locale.ROOT);
        String program = values[1].strip().toUpperCase(Locale.ROOT);
        // A symbol nothing resolved names no program a reader could look for.
        if (!APPLICATION_REGIONS.contains(region) || program.isEmpty() || program.contains("&")) {
            return Optional.empty();
        }
        String psb = values.length > 2 ? values[2].strip().toUpperCase(Locale.ROOT) : "";
        if (psb.isEmpty() || psb.contains("&")) {
            psb = program;
        }
        return Optional.of(new ProgramRun(program, Optional.empty(), Optional.empty(),
                Optional.empty(), ProgramRun.IMS_PREFIX + region, Optional.of(psb)));
    }
}
