package jp.cobolinsight.engineapi.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;

import java.util.Optional;
import java.util.Set;

/**
 * 1プログラムの不動点解析結果。問い合わせは {@link CfgNode} の同一性で引く(結果を算出した
 * CFG と同一インスタンスを渡す前提)。変数名は正規化(大文字化)して照合する。
 *
 * <p>rules(本番依存が engine-api のみ)が消費するため、契約型を engine-api に置く。生成は
 * dataflow モジュールが担い、{@link jp.cobolinsight.engineapi.cfg.ControlFlowGraphs} と同じ
 * 「型=engine-api・生成=dataflow・消費=rules」の配置を踏襲する。
 */
public interface ProgramDataFlow {

    /** 対象プログラムの PROGRAM-ID。 */
    String programId();

    /**
     * VALUE 句を持たない WORKING-STORAGE / LINKAGE 項目が、未初期化のまま useNode の入口へ
     * 到達し得るか(R001)。到達定義解析で入口に合成した「未初期化定義」が届くかで判定する。
     */
    boolean mayReachUninitialized(CfgNode useNode, String varName);

    /** ノード入口での varName の整数区間(R005/R028)。追跡対象外は empty。 */
    Optional<ValueInterval> intervalAt(CfgNode node, String varName);

    /** ノード入口で当該種別の汚染下にある変数(R020=EXTERNAL_INPUT、R027=SENSITIVE)。 */
    Set<String> taintedAt(CfgNode node, TaintKind kind);

    /** このノードで定義(代入)される変数。 */
    Set<String> defsAt(CfgNode node);

    /** このノードで参照される変数。 */
    Set<String> usesAt(CfgNode node);

    /** ノード出口で生存する変数。 */
    Set<String> liveOut(CfgNode node);
}
