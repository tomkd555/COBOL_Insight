import { useEffect, useMemo, useState, type ReactElement } from "react";
import type { SarifFinding } from "../../../../shared/engine-api";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import type { FixDecision } from "../../state/appState";
import { SCREEN_META } from "../screenMeta";
import { DiffPane } from "./DiffPane";
import { FixList } from "./FixList";
import {
  analysisWarning,
  applyCaution,
  applyNotice,
  buildFixCandidates,
  candidateLocation,
  candidateRuleSummary,
  decisionCounts,
  deriveDiffView,
  extractUnifiedDiff,
  fixOutputPaths,
  fixRuleDescriptionLabel,
  fixRuleIdLabel,
  joinPath,
  readFixSummary,
  reparseWarning,
  resolveSelection,
  type DiffTextState,
  type FixApplyOutcome,
  type FixState,
} from "./diffModel";

/** 修正案の生成中に提示する段。engine は生成した修正後ソースを再構文解析して検証する。 */
const FIX_RUN_STAGES = ["修正後ソースの再構文解析による妥当性確認を含む"];

/**
 * 未取得のときに使う値。参照を固定して useMemo と useEffect の依存を安定させる
 * (毎回 [] や新しい object を作ると依存が変わり続け、効果が止まらなくなる)。
 */
const IDLE_FIX: FixState = { status: "idle" };
const IDLE_DIFF: DiffTextState = { status: "idle" };
const EMPTY_FINDINGS: readonly SarifFinding[] = [];

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * diff(fix)画面。engine の `fix preview` が出した修正案の一覧を左に並べ、選択した1件の差分を
 * Monaco DiffEditor の左右2ペインで示す。差分は GUI で計算せず、engine が書いた原本テキストと
 * 修正後テキストの対(readFixResult)をそのまま渡す。
 *
 * 修正後ソースの実体化は、差分表示のために検証用の作業フォルダへ `fix apply` で書き出して行う。
 * 原本はどちらの経路でも変更されない。engine はコピー句の修正後ソースを書き出さないため、
 * コピー句の差分は `fix preview` が標準出力へ書いた unified diff を素の文字列として示す。
 *
 * 4状態は実状態から導く。empty=解析未実行、running=解析実行中または修正案の生成中、
 * results=修正案を取得済み、error=`fix preview` の起動失敗である。
 */
export function DiffScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.diff;
  const { inputDir, dbPath, copybookPaths } = state.project;
  const paths = useMemo(() => fixOutputPaths(dbPath), [dbPath]);

  // 画面内に持つのは engine 呼出の取得状態だけとし、選択・判定・表示モードは AppState を正とする。
  const [fix, setFix] = useState<FixState>(IDLE_FIX);
  const [diff, setDiff] = useState<DiffTextState>(IDLE_DIFF);
  const [applying, setApplying] = useState(false);
  const [applyOutcome, setApplyOutcome] = useState<FixApplyOutcome | null>(null);
  const { fixSelected, diffMode, decisions } = state;

  const analyzed = state.mode === "results" || state.mode === "error";
  const findings = state.findings.status === "ready" ? state.findings.items : EMPTY_FINDINGS;
  const summary = fix.status === "ready" ? fix.summary : null;
  const candidates = useMemo(
    () => (summary === null ? [] : buildFixCandidates(summary, findings)),
    [summary, findings],
  );
  const candidate = useMemo(() => resolveSelection(candidates, fixSelected), [candidates, fixSelected]);
  const counts = useMemo(() => decisionCounts(candidates, decisions), [candidates, decisions]);

  // 解析済みで修正案が未取得なら、この画面が fix を起動して修正案を作る。
  useEffect(() => {
    if (!analyzed || inputDir === null || fix.status !== "idle") {
      return;
    }
    void load(inputDir);
  }, [analyzed, inputDir, fix.status]);

  /**
   * 修正案を作る。`fix preview` で一覧と unified diff を得たうえで、差分表示に要する修正後ソースを
   * 検証用の作業フォルダへ実体化する。実体化に失敗しても一覧と unified diff は示せるため、
   * 修正案の取得そのものは失敗にしない。
   */
  async function load(dir: string): Promise<void> {
    setFix({ status: "loading" });
    try {
      const preview = await window.cobolInsight.runFixPreview({
        inputDir: dir,
        copybookPaths,
      });
      const previewSummary = readFixSummary(preview.summary);
      let reparseFailures: number | null = null;
      let materializedDir: string | null = null;
      if (previewSummary.files.length > 0) {
        try {
          const applied = await window.cobolInsight.runFixApply({
            inputDir: dir,
            copybookPaths,
            outDir: paths.previewDir,
          });
          reparseFailures = readFixSummary(applied.summary).reparseFailures ?? null;
          materializedDir = applied.outputs.outDir ?? paths.previewDir;
        } catch {
          // 実体化できないときは unified diff の表示へ退避する。
        }
      }
      setFix({
        status: "ready",
        summary: previewSummary,
        stdout: preview.stdout,
        reparseFailures,
        materializedDir,
      });
    } catch (error) {
      setFix({ status: "error", message: messageOf(error) });
    }
  }

  // 選択した修正案の差分を取り直す。古い応答は捨てる。
  useEffect(() => {
    if (fix.status !== "ready" || candidate === null || inputDir === null) {
      setDiff(IDLE_DIFF);
      return;
    }
    if (candidate.copybook || fix.materializedDir === null) {
      setDiff({
        status: "unified",
        lines: extractUnifiedDiff(fix.stdout, candidate.relPath),
        reason: candidate.copybook
          ? "コピー句の修正は原本を書き換えないため、修正後ソースは書き出されない。修正案の生成が出した差分をそのまま示す。"
          : "修正後ソースを実体化できなかったため、修正案の生成が出した差分をそのまま示す。",
      });
      return;
    }
    let current = true;
    setDiff({ status: "loading" });
    window.cobolInsight
      .readFixResult({
        originalPath: joinPath(inputDir, candidate.relPath),
        fixedPath: joinPath(fix.materializedDir, candidate.relPath),
        relPath: candidate.relPath,
      })
      .then((result) => {
        if (current) {
          setDiff({
            status: "ready",
            originalText: result.originalText,
            fixedText: result.fixedText,
          });
        }
      })
      .catch((error: unknown) => {
        if (current) setDiff({ status: "error", message: messageOf(error) });
      });
    return () => {
      current = false;
    };
  }, [fix, candidate, inputDir]);

  /** 判定を記録する。engine は書き出しを選べないため、判定は人の判断の記録として保つ。 */
  function decide(decision: FixDecision): void {
    if (candidate === null) return;
    dispatch({ type: "SET_FIX_DECISION", relPath: candidate.relPath, decision });
    dispatch({
      type: "SHOW_TOAST",
      message:
        decision === "adopted"
          ? `${candidate.relPath} の修正案を採用と記録しました。`
          : `${candidate.relPath} の修正案を棄却と記録しました。`,
    });
  }

  /** 修正版を出力先へ書き出す。engine が全件を書き出し、原本は変更しない。 */
  async function writeFix(): Promise<void> {
    if (inputDir === null) return;
    setApplying(true);
    try {
      const result = await window.cobolInsight.runFixApply({
        inputDir,
        copybookPaths,
        outDir: paths.applyDir,
      });
      const applied = readFixSummary(result.summary);
      const outcome: FixApplyOutcome = {
        outDir: result.outputs.outDir ?? paths.applyDir,
        written: applied.files,
        copybookFixes: applied.copybookFixes,
        reparseFailures: applied.reparseFailures ?? 0,
      };
      setApplyOutcome(outcome);
      dispatch({ type: "SHOW_TOAST", message: applyNotice(outcome) });
    } catch (error) {
      dispatch({
        type: "SHOW_TOAST",
        message: `修正版の書き出しに失敗しました。${messageOf(error)}`,
      });
    } finally {
      setApplying(false);
    }
  }

  const view = deriveDiffView(state.mode, inputDir, fix);
  if (view.kind === "empty") {
    return (
      <EmptyState
        title="修正案がありません"
        description={`資産をインポートして解析を実行すると、${fixRuleDescriptionLabel()}の修正案を生成する。他の指摘は助言のみで、差分は生成されない。`}
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (view.kind === "running") {
    return <RunningIndicator title={meta.runningTitle} stages={FIX_RUN_STAGES} />;
  }
  if (view.kind === "no-project") {
    return (
      <EmptyState
        title="資産フォルダが選ばれていません"
        description="資産エクスプローラーで資産フォルダをインポートすると、修正案を生成できる。"
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (view.kind === "error") {
    return (
      <EmptyState
        icon="！"
        title="修正案を生成できませんでした"
        description={`修正案の生成に失敗した。${view.message}`}
        actionLabel="再試行"
        onAction={() => setFix(IDLE_FIX)}
      />
    );
  }
  if (candidate === null) {
    return (
      <EmptyState
        title="修正案は生成されませんでした"
        description={`解析した資産に、${fixRuleIdLabel()} の修正案を生成できる指摘は見つからなかった。他の指摘は助言のみで、差分は生成されない。`}
        actionLabel="修正案を作り直す"
        onAction={() => setFix(IDLE_FIX)}
      />
    );
  }

  const reparse = fix.status === "ready" ? reparseWarning(fix.reparseFailures) : null;
  const analysis = summary === null ? null : analysisWarning(summary);
  const caution = applyCaution(counts);
  const preview = diffMode === "preview";

  return (
    <div className="ci-diff">
      <FixList
        candidates={candidates}
        selected={candidate.relPath}
        decisions={decisions}
        onSelect={(relPath) => dispatch({ type: "SELECT_FIX", relPath })}
      />
      <div className="ci-diff__main">
        <div className="ci-diff__toolbar">
          <div className="ci-diff__heading">
            <h3 className="ci-diff__title">{`${candidateRuleSummary(candidate)} ― ${candidateLocation(candidate)}`}</h3>
            <p className="ci-diff__subtitle">
              {`判定: 採用 ${counts.adopted} ・ 棄却 ${counts.rejected} ・ 未判定 ${counts.pending}`}
            </p>
          </div>
          <div className="ci-diff__spacer" />
          <div className="ci-diff__modes" role="group" aria-label="差分の表示モード">
            <Button
              variant={preview ? "primary" : "default"}
              aria-pressed={preview}
              onClick={() => dispatch({ type: "SET_DIFF_MODE", mode: "preview" })}
            >
              プレビュー（確認）
            </Button>
            <Button
              variant={preview ? "default" : "primary"}
              aria-pressed={!preview}
              onClick={() => dispatch({ type: "SET_DIFF_MODE", mode: "apply" })}
            >
              適用（書き出し）
            </Button>
          </div>
          <Button
            aria-label="採用"
            variant="primary"
            aria-pressed={decisions[candidate.relPath] === "adopted"}
            onClick={() => decide("adopted")}
          >
            <span aria-hidden="true">✓</span> 採用
          </Button>
          <Button
            aria-label="棄却"
            aria-pressed={decisions[candidate.relPath] === "rejected"}
            onClick={() => decide("rejected")}
          >
            <span aria-hidden="true">✗</span> 棄却
          </Button>
        </div>

        {reparse === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {reparse}
          </div>
        )}
        {analysis === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {analysis}
          </div>
        )}
        {candidate.copybook ? (
          <div className="ci-diff__impact" role="note">
            <p className="ci-diff__impact-title">
              コピー句内の修正 ― この変更は当該コピー句を取り込む全プログラムへ波及する
            </p>
            <p className="ci-diff__impact-progs">{`取込プログラム: ${candidate.importers.join("、 ")}`}</p>
          </div>
        ) : null}

        {preview ? null : (
          <div className="ci-diff__apply">
            <p className="ci-diff__apply-label">書き出し先（原本は変更しない）:</p>
            <p className="ci-diff__apply-path">{paths.applyDir}</p>
            <div className="ci-diff__spacer" />
            <Button variant="primary" disabled={applying} onClick={() => void writeFix()}>
              {applying ? "書き出している…" : "修正版を書き出す"}
            </Button>
          </div>
        )}
        {!preview && caution !== null ? (
          <p className="ci-diff__caution" role="note">
            {caution}
          </p>
        ) : null}
        {!preview && applyOutcome !== null ? (
          <p className="ci-diff__result" role="status">
            {applyNotice(applyOutcome)}
          </p>
        ) : null}

        <div className="ci-diff__labels">
          <span className="ci-diff__label">修正前（原本 ― 変更しない）</span>
          <span className="ci-diff__label">修正後（解析エンジンが生成した修正後ソース）</span>
        </div>
        <div className="ci-diff__body">
          {diff.status === "loading" ? (
            <p className="ci-diff__loading" role="status">
              差分を読み込んでいる…
            </p>
          ) : diff.status === "error" ? (
            <EmptyState
              icon="！"
              title="差分を読み取れませんでした"
              description={diff.message}
            />
          ) : diff.status === "unified" ? (
            <div className="ci-diff__unified">
              <p className="ci-diff__unified-note">{diff.reason}</p>
              {diff.lines.length === 0 ? (
                <p className="ci-diff__unified-empty">
                  この修正案の差分は、生成された差分の出力に含まれていない。
                </p>
              ) : (
                <pre className="ci-diff__unified-body" aria-label={`統一形式の差分 ${candidate.relPath}`}>
                  {diff.lines.join("\n")}
                </pre>
              )}
            </div>
          ) : diff.status === "ready" ? (
            <DiffPane
              originalText={diff.originalText}
              fixedText={diff.fixedText}
              ariaLabel={`修正案の差分 ${candidate.relPath}`}
            />
          ) : null}
        </div>
      </div>
    </div>
  );
}
