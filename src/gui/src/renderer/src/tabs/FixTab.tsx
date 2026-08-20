import { useCallback, useEffect, useMemo, useState, type CSSProperties, type ReactElement } from "react";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { RunningIndicator } from "../components/RunningIndicator";
import { SplitHandle } from "../components/SplitHandle";
import { DiffPane } from "../screens/diff/DiffPane";
import { FixList } from "../screens/diff/FixList";
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
  mergeWarnings,
  readFixSummary,
  reparseWarning,
  resolveSelection,
  setDecision,
  type DiffTextState,
  type FixApplyOutcome,
  type FixDecision,
} from "../screens/diff/diffModel";
import { messageOf } from "../services/analysis";
import { artifactItems, useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings } from "../state/settingsStore";

/** 修正案の生成中に提示する段。engine は生成した修正後ソースを再構文解析して検証する。 */
const FIX_RUN_STAGES = ["修正後ソースの再構文解析による妥当性の確認を含みます"];

/** 未取得のときに使う値。参照を固定して効果の依存を安定させる。 */
const IDLE_DIFF: DiffTextState = { status: "idle" };

/** 一覧の寸法。min は一覧が役目を果たす最小、oppositeMin は差分の2面へ必ず残す最小である。 */
const LIST_LIMITS = { initial: 300, min: 220, oppositeMin: 560 } as const;

/**
 * 修正案のタブ。engine の `fix preview` が出した修正案を一覧に並べ、選んだ1件の差分を Monaco の
 * 左右2ペインで示す。差分は画面で計算せず、engine が書いた原本テキストと修正後テキストの対を渡す。
 *
 * 修正後ソースの実体化は、差分表示のために検証用の作業フォルダへ `fix apply` で書き出して行う。
 * 原本はどちらの経路でも変わらない。
 */
export function FixTab(): ReactElement {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const settings = useSettings();
  const { inputDir, dbPath, fix, fixDecisions } = project;
  const paths = useMemo(() => fixOutputPaths(dbPath), [dbPath]);

  const [selected, setSelected] = useState<string>("");
  const [preview, setPreview] = useState(true);
  const [diff, setDiff] = useState<DiffTextState>(IDLE_DIFF);
  const [applying, setApplying] = useState(false);
  const [applyOutcome, setApplyOutcome] = useState<FixApplyOutcome | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [listWidth, setListWidth] = useState<number>(LIST_LIMITS.initial);

  const analyzed = project.mode === "results" || project.mode === "error";
  const findings = artifactItems(project.findings);
  const summary = fix.status === "ready" ? fix.summary : null;
  const candidates = useMemo(
    () => (summary === null ? [] : buildFixCandidates(project.catalog, summary, findings)),
    [project.catalog, summary, findings],
  );
  const candidate = useMemo(() => resolveSelection(candidates, selected), [candidates, selected]);
  const counts = useMemo(() => decisionCounts(candidates, fixDecisions), [candidates, fixDecisions]);

  /**
   * 修正案を作る。`fix preview` で一覧と統一形式の差分を得たうえで、差分表示に要する修正後ソースを
   * 検証用の作業フォルダへ実体化する。実体化に失敗しても一覧と差分は示せるため、取得そのものは
   * 失敗にしない。
   */
  const load = useCallback(
    async (dir: string): Promise<void> => {
      projectDispatch({ type: "SET_FIX", fix: { status: "loading" } });
      const copybookPaths = [...settings.copybookPaths];
      try {
        const previewRun = await window.cobolInsight.runFixPreview({
          inputDir: dir,
          copybookPaths,
        });
        const previewSummary = readFixSummary(previewRun.summary);
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
            // 実体化できないときは統一形式の差分の表示へ退避する。
          }
        }
        projectDispatch({
          type: "SET_FIX",
          fix: {
            status: "ready",
            summary: previewSummary,
            stdout: previewRun.stdout,
            reparseFailures,
            materializedDir,
          },
        });
      } catch (error) {
        projectDispatch({ type: "SET_FIX", fix: { status: "error", message: messageOf(error) } });
      }
    },
    [projectDispatch, settings.copybookPaths, paths.previewDir],
  );

  // 解析済みで修正案が未取得なら、このタブが fix を起こして修正案を作る。
  useEffect(() => {
    if (!analyzed || inputDir === null || fix.status !== "idle") {
      return;
    }
    void load(inputDir);
  }, [analyzed, inputDir, fix.status, load]);

  // 選んだ修正案の差分を取り直す。古い応答は捨てる。
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
          ? "コピー句の修正は原本を書き換えないため、修正後ソースは書き出されません。修正案の生成が出した差分をそのまま示します。"
          : "修正後ソースを実体化できなかったため、修正案の生成が出した差分をそのまま示します。",
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
    projectDispatch({
      type: "SET_FIX_DECISIONS",
      decisions: setDecision(fixDecisions, candidate.relPath, decision),
    });
  }

  /** 修正版を出力先へ書き出す。engine が全件を書き出し、原本は変わらない。 */
  async function writeFix(): Promise<void> {
    if (inputDir === null) return;
    setApplying(true);
    setApplyError(null);
    try {
      const result = await window.cobolInsight.runFixApply({
        inputDir,
        copybookPaths: [...settings.copybookPaths],
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
      projectDispatch({ type: "LOG", text: applyNotice(outcome) });
    } catch (error) {
      const message = `修正版を書き出せませんでした。${messageOf(error)} 出力先の書き込み権限を確かめて、もう一度書き出してください。`;
      setApplyError(message);
      projectDispatch({ type: "LOG", text: message, failed: true });
    } finally {
      setApplying(false);
    }
  }

  const view = deriveDiffView(project.mode, inputDir, fix);
  if (view.kind === "empty") {
    return (
      <EmptyState
        title="修正案がありません"
        description={`資産フォルダを解析すると、${fixRuleDescriptionLabel(project.catalog)}の修正案を生成します。他の指摘は検出だけで、差分は生成しません。`}
      />
    );
  }
  if (view.kind === "running") {
    return <RunningIndicator title="修正案を生成しています" stages={FIX_RUN_STAGES} />;
  }
  if (view.kind === "no-project") {
    return (
      <EmptyState
        title="資産フォルダを選んでいません"
        description="エクスプローラーで資産フォルダを選ぶと、修正案を生成できます。"
      />
    );
  }
  if (view.kind === "error") {
    return (
      <EmptyState
        icon="！"
        title="修正案を生成できませんでした"
        description={`${view.message} 資産フォルダとコピー句の探索パスを確かめて、もう一度試してください。`}
        actionLabel="もう一度生成"
        onAction={() => projectDispatch({ type: "SET_FIX", fix: { status: "idle" } })}
      />
    );
  }
  if (candidate === null) {
    return (
      <EmptyState
        title="修正案は生成されませんでした"
        description={`解析した資産に、${fixRuleIdLabel()} の修正案を生成できる指摘はありませんでした。他の指摘は検出だけで、差分は生成しません。`}
        actionLabel="修正案を作り直す"
        onAction={() => projectDispatch({ type: "SET_FIX", fix: { status: "idle" } })}
      />
    );
  }

  const warning = mergeWarnings(
    fix.status === "ready" ? reparseWarning(fix.reparseFailures) : null,
    summary === null ? null : analysisWarning(summary),
  );
  const caution = applyCaution(counts);
  // 一覧の幅と、差分の2面へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  const paneStyle = {
    "--ci-diff-list-w": `${listWidth}px`,
    "--ci-opposite-min": `${LIST_LIMITS.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-diff" style={paneStyle}>
      <FixList
        catalog={project.catalog}
        candidates={candidates}
        selected={candidate.relPath}
        decisions={fixDecisions}
        onSelect={setSelected}
      />
      <SplitHandle
        size={listWidth}
        min={LIST_LIMITS.min}
        oppositeMin={LIST_LIMITS.oppositeMin}
        side="before"
        ariaLabel="修正案一覧の幅"
        onSizeChange={setListWidth}
      />
      <div className="ci-diff__main">
        <div className="ci-diff__toolbar">
          <div className="ci-diff__heading">
            <h3 className="ci-diff__title">
              {`${candidateRuleSummary(project.catalog, candidate)} ― ${candidateLocation(candidate)}`}
            </h3>
            <span className="ci-diff__subtitle">
              {`判定: 採用 ${counts.adopted} ・ 棄却 ${counts.rejected} ・ 未判定 ${counts.pending}`}
            </span>
          </div>
          <div className="ci-diff__spacer" />
          <div className="ci-diff__modes" role="group" aria-label="差分の表示">
            <Button
              variant={preview ? "primary" : "default"}
              aria-pressed={preview}
              onClick={() => setPreview(true)}
            >
              確認
            </Button>
            <Button
              variant={preview ? "default" : "primary"}
              aria-pressed={!preview}
              onClick={() => setPreview(false)}
            >
              書き出し
            </Button>
          </div>
          <Button
            aria-label="採用"
            variant="primary"
            aria-pressed={fixDecisions[candidate.relPath] === "adopted"}
            onClick={() => decide("adopted")}
          >
            <span aria-hidden="true">✓</span> 採用
          </Button>
          <Button
            aria-label="棄却"
            aria-pressed={fixDecisions[candidate.relPath] === "rejected"}
            onClick={() => decide("rejected")}
          >
            <span aria-hidden="true">✗</span> 棄却
          </Button>
          {preview ? null : (
            <Button variant="primary" disabled={applying} onClick={() => void writeFix()}>
              {applying ? "書き出しています…" : "修正版を書き出す"}
            </Button>
          )}
        </div>

        {warning === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {warning}
          </div>
        )}
        {applyError === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {applyError}
          </div>
        )}
        {candidate.copybook ? (
          <details className="ci-diff__impact">
            <summary className="ci-diff__impact-title">
              コピー句内の修正 ― この変更は当該コピー句を組み込む全プログラムへ波及する
            </summary>
            <p className="ci-diff__impact-progs">{`組み込み元プログラム: ${candidate.importers.join("、 ")}`}</p>
          </details>
        ) : null}

        {preview ? null : (
          <p className="ci-diff__apply" role="note">
            {`書き出し先（原本は変えません）: ${paths.applyDir}${caution === null ? "" : ` ${caution}`}`}
          </p>
        )}
        {!preview && applyOutcome !== null ? (
          <p className="ci-diff__result" role="status">
            {applyNotice(applyOutcome)}
          </p>
        ) : null}

        <div className="ci-diff__labels">
          <span className="ci-diff__label">修正前（原本 ― 変えません）</span>
          <span className="ci-diff__label">修正後（解析エンジンが生成した修正後ソース）</span>
        </div>
        <div className="ci-diff__body">
          {diff.status === "loading" ? (
            <p className="ci-diff__loading" role="status">
              差分を読み込んでいます…
            </p>
          ) : diff.status === "error" ? (
            <EmptyState
              icon="！"
              title="差分を読み取れませんでした"
              description={`${diff.message} 修正案を作り直すと、差分を取り直せます。`}
            />
          ) : diff.status === "unified" ? (
            <div className="ci-diff__unified">
              <p className="ci-diff__unified-note">{diff.reason}</p>
              {diff.lines.length === 0 ? (
                <p className="ci-diff__unified-empty">
                  この修正案の差分は、生成された差分の出力に含まれていません。
                </p>
              ) : (
                <pre
                  className="ci-diff__unified-body"
                  aria-label={`統一形式の差分 ${candidate.relPath}`}
                >
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
