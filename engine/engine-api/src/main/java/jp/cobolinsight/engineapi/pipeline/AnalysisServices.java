package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.JclParser;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.SqlParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 解析パイプラインのファサード。各契約インターフェースの実装を ServiceLoader で束ねる。
 * engine-api は実装モジュールへコンパイル依存を持たず、cli が実行時クラスパスへ実装一式を
 * 同梱してパイプラインを成立させる。ルールは id 昇順に正規化して保持する。
 */
public final class AnalysisServices {

    private final List<CobolParser> cobolParsers;
    private final List<JclParser> jclParsers;
    private final List<SqlParser> sqlParsers;
    private final List<CharsetProvider> charsetProviders;
    private final List<Rule> rules;

    public AnalysisServices(List<CobolParser> cobolParsers, List<JclParser> jclParsers,
            List<SqlParser> sqlParsers, List<CharsetProvider> charsetProviders, List<Rule> rules) {
        this.cobolParsers = List.copyOf(cobolParsers);
        this.jclParsers = List.copyOf(jclParsers);
        this.sqlParsers = List.copyOf(sqlParsers);
        this.charsetProviders = List.copyOf(charsetProviders);
        List<Rule> sortedRules = new ArrayList<>(rules);
        sortedRules.sort(Comparator.comparing(Rule::id));
        Set<String> ids = new HashSet<>();
        for (Rule rule : sortedRules) {
            if (!ids.add(rule.id())) {
                throw new IllegalStateException("duplicate rule id: " + rule.id());
            }
        }
        this.rules = List.copyOf(sortedRules);
    }

    public static AnalysisServices load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AnalysisServices.class.getClassLoader();
        }
        return load(loader);
    }

    public static AnalysisServices load(ClassLoader loader) {
        return new AnalysisServices(
                loadAll(CobolParser.class, loader),
                loadAll(JclParser.class, loader),
                loadAll(SqlParser.class, loader),
                loadAll(CharsetProvider.class, loader),
                loadAll(Rule.class, loader));
    }

    private static <T> List<T> loadAll(Class<T> service, ClassLoader loader) {
        List<T> implementations = new ArrayList<>();
        ServiceLoader.load(service, loader).forEach(implementations::add);
        return implementations;
    }

    public List<CobolParser> cobolParsers() {
        return cobolParsers;
    }

    public List<JclParser> jclParsers() {
        return jclParsers;
    }

    public List<SqlParser> sqlParsers() {
        return sqlParsers;
    }

    public List<CharsetProvider> charsetProviders() {
        return charsetProviders;
    }

    /** ServiceLoader が読み込んだ全ルール(id 昇順)。 */
    public List<Rule> rules() {
        return rules;
    }

    /** 指定した解析段階のルール(id 昇順)。 */
    public List<Rule> rules(AnalysisPhase phase) {
        return rules.stream().filter(rule -> rule.phase() == phase).toList();
    }
}
