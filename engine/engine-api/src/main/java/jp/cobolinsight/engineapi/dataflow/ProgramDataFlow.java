package jp.cobolinsight.engineapi.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 1プログラムの不動点解析結果。問い合わせは {@link CfgNode} の同一性で引く(結果を算出した
 * CFG と同一インスタンスを渡す前提)。変数名は正規化(大文字化)して照合する。
 *
 * <p>生成は dataflow モジュールが担い、消費は rules が担う。rules は dataflow へ依存しないため、
 * 両者をつなぐ契約型は双方が依存する engine-api に置く。
 * {@link jp.cobolinsight.engineapi.cfg.ControlFlowGraphs} も同じ配置を採る。
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

    /**
     * ノード入口で汚染下にある varName について、汚染源から当該ノードの入口に至るまでに汚染を
     * 得たノードの列(汚染源が先頭)。問い合わせたノード自身は含まない。汚染下でない場合は空。
     *
     * <p>may 解析のため経路は複数あり得る。返すのは汚染源に根を持つ最短の1本であり、経路の
     * 網羅ではない。機密名義(SENSITIVE)の汚染源はデータ部の宣言であって文を持たないため、
     * 経路は宣言項目からの最初の代入から始まる。
     */
    List<TaintStep> taintPathTo(CfgNode node, String varName, TaintKind kind);

    /** このノードで定義(代入)される変数。 */
    Set<String> defsAt(CfgNode node);

    /** このノードで参照される変数。 */
    Set<String> usesAt(CfgNode node);

    /** ノード出口で生存する変数。 */
    Set<String> liveOut(CfgNode node);
}
