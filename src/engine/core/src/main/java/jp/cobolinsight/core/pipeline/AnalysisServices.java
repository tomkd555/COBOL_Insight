package jp.cobolinsight.core.pipeline;

import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.SqlParser;

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
        return load(List.of());
    }

    /**
     * ServiceLoader が見つけるルールへ、実行時に組み立てたルール(利用者定義ルール)を合流させる。
     * ID の重複はコンストラクタが例外で拒むため、組み込みルールと同じ ID は使えない。
     */
    public static AnalysisServices load(List<Rule> additionalRules) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AnalysisServices.class.getClassLoader();
        }
        return load(loader, additionalRules);
    }

    public static AnalysisServices load(ClassLoader loader) {
        return load(loader, List.of());
    }

    public static AnalysisServices load(ClassLoader loader, List<Rule> additionalRules) {
        List<Rule> rules = new ArrayList<>(loadAll(Rule.class, loader));
        rules.addAll(additionalRules);
        return new AnalysisServices(
                loadAll(CobolParser.class, loader),
                loadAll(JclParser.class, loader),
                loadAll(SqlParser.class, loader),
                loadAll(CharsetProvider.class, loader),
                rules);
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
