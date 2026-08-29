package jp.cobolinsight.app;

import jp.cobolinsight.core.encoding.EncodingCharsetProvider;
import jp.cobolinsight.core.fix.ReparseVerifier;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.frontend.bms.BmsSourceParser;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.frontend.jcl.MapaJclParser;
import jp.cobolinsight.frontend.sql.JsqlSqlParser;
import jp.cobolinsight.rules.BuiltinRules;

import java.util.List;

/**
 * Where the engine's parts are named. Everything the pipeline runs on is constructed here by
 * hand: a frontend that is not listed does not load, instead of a missing service file quietly
 * disabling a whole asset kind at run time while the build still passes.
 */
public final class EngineWiring {

    private EngineWiring() {
    }

    public static CharsetProvider charsetProvider() {
        return new EncodingCharsetProvider();
    }

    public static CobolParser cobolParser() {
        return new Che4zCobolParser();
    }

    public static JclParser jclParser() {
        return new MapaJclParser();
    }

    public static SqlParser sqlParser() {
        return new JsqlSqlParser();
    }

    public static BmsSourceParser bmsParser() {
        return new BmsSourceParser();
    }

    /** The verifier that reparses a fixed source, wired to the same frontend as the analysis. */
    public static ReparseVerifier reparseVerifier() {
        return new ReparseVerifier(cobolParser(), charsetProvider());
    }

    public static List<Rule> builtinRules() {
        return BuiltinRules.all();
    }
}
