import { useMemo, useState, type ReactElement, type ReactNode } from "react";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { deriveRunBanner } from "../../state/status";
import { disabledRuleIds } from "../settings/settingsModel";
import { SCREEN_META } from "../screenMeta";
import { ReportForm } from "./ReportForm";
import { ReportPreview } from "./ReportPreview";
import {
  deriveReportView,
  exitCodeWarning,
  previewPath,
  readReportSummary,
  reportArtifactPaths,
  writeNotice,
  type ReportState,
} from "./reportModel";

/** レポート生成中に提示する段。 */
const REPORT_RUN_STAGES = ["資産の走査結果と、指摘・SQL助言の再検出を1つの文書へ束ねている"];

/** 未生成のときの状態。参照を固定して依存を安定させる。 */
const IDLE_REPORT: ReportState = { status: "idle" };

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * レポート出力(report)画面。engine の `report` を起動して HTML とテキストを書き出し、書いたものを
 * readReportHtml / readReportText で読んで表示する。レポート本文は GUI で組み立てない。
 *
 * HTML は sandbox 付き iframe の srcdoc で表示し、スクリプトを実行させない。表示の形式切替は
 * engine が同時に書いた2つの成果物のどちらを見るかの選択であり、engine の再実行を伴わない。
 *
 * 4状態は実状態から導く。empty=解析未実行、running=解析実行中またはレポート生成中、
 * results=レポート取得済み、error=`report` の起動または成果物の読取の失敗である。
 */
export function ReportScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.report;
  const { inputDir, dbPath, copybookPaths } = state.project;

  // 出力先の未指定(空文字)はプロジェクトファイルの置き場所を指す。scan が書けた場所であり、
  // 確実に存在するためである。
  const defaultDir = useMemo(() => reportArtifactPaths(dbPath).dir, [dbPath]);
  const outDir = state.reportPath === "" ? defaultDir : state.reportPath;
  const format = state.reportFormat;
  // 画面内に持つのは engine 呼出の取得状態だけとし、形式と出力先は AppState を正とする。
  const [report, setReport] = useState<ReportState>(IDLE_REPORT);

  const disabledRules = useMemo(() => disabledRuleIds(state.rulesDisabled), [state.rulesDisabled]);

  /** report を起動して HTML とテキストを書き、書いたものを読んで表示する。 */
  async function writeReport(): Promise<void> {
    if (inputDir === null) return;
    const paths = reportArtifactPaths(dbPath, outDir);
    setReport({ status: "loading" });
    try {
      const result = await window.cobolInsight.runReport({
        inputDir,
        copybookPaths,
        db: paths.db,
        htmlFile: paths.html,
        textFile: paths.text,
        disabledRules,
      });
      const [html, text] = await Promise.all([
        window.cobolInsight.readReportHtml(paths.html),
        window.cobolInsight.readReportText(paths.text),
      ]);
      setReport({
        status: "ready",
        summary: readReportSummary(result.summary),
        html,
        text,
        paths,
      });
      dispatch({ type: "SHOW_TOAST", message: writeNotice(paths) });
    } catch (error) {
      setReport({ status: "error", message: messageOf(error) });
    }
  }

  // 解析実行の部分的失敗は、レポートの内容が欠ける原因になるためこの画面でも示す。
  const runBanner = deriveRunBanner(state);

  /** 書き出しの操作ができない状態でも、画面の見出しと解析の失敗を示す枠。 */
  function placeholder(children: ReactNode): ReactElement {
    return (
      <div className="ci-report ci-report--placeholder">
        <h3 className="ci-report__title">レポート出力</h3>
        {runBanner === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {runBanner}
          </div>
        )}
        {children}
      </div>
    );
  }

  const view = deriveReportView(state.mode, inputDir, report);
  if (view.kind === "empty") {
    return placeholder(
      <EmptyState
        title="出力できる解析結果がありません"
        description="資産をインポートして解析を実行すると、呼出関係サマリ・指摘一覧・SQL助言をレポートとして書き出せる。"
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />,
    );
  }
  if (view.kind === "running") {
    return placeholder(<RunningIndicator title={meta.runningTitle} stages={REPORT_RUN_STAGES} />);
  }
  if (view.kind === "no-project") {
    return placeholder(
      <EmptyState
        title="資産フォルダが選ばれていません"
        description="資産エクスプローラーで資産フォルダをインポートすると、レポートを書き出せる。"
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />,
    );
  }

  const busy = report.status === "loading";
  const error = view.kind === "error" ? view.message : null;
  const warning = report.status === "ready" ? exitCodeWarning(report.summary.exitCode) : runBanner;

  return (
    <div className="ci-report">
      <ReportForm
        format={format}
        onFormatChange={(next) => dispatch({ type: "SET_REPORT_FORMAT", format: next })}
        outDir={outDir}
        onOutDirChange={(value) => dispatch({ type: "SET_REPORT_PATH", value })}
        disabledRuleCount={disabledRules.length}
        onWrite={() => void writeReport()}
        busy={busy}
        error={error === null ? null : `レポートを書き出せなかった。${error}`}
      />
      <div className="ci-report__body">
        {warning === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {warning}
          </div>
        )}
        {report.status === "ready" ? (
          <ReportPreview
            format={format}
            html={report.html}
            text={report.text}
            summary={report.summary}
            path={previewPath(format, report.paths)}
          />
        ) : (
          <EmptyState
            title="レポートはまだ書き出されていません"
            description="出力先フォルダを確かめて「レポートを書き出す」を押すと、解析エンジンが HTML とテキストを書き出し、その内容をここに表示する。"
          />
        )}
      </div>
    </div>
  );
}
