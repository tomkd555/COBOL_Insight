import { useMemo, useState, type ReactElement, type ReactNode } from "react";
import { EmptyState } from "../components/EmptyState";
import { RunningIndicator } from "../components/RunningIndicator";
import { ReportForm } from "../screens/report/ReportForm";
import { ReportPreview } from "../screens/report/ReportPreview";
import {
  deriveReportView,
  exitCodeWarning,
  previewPath,
  readReportSummary,
  reportArtifactPaths,
  writeNotice,
  type ReportFormat,
  type ReportState,
} from "../screens/report/reportModel";
import { disabledRuleIds } from "../screens/settings/settingsModel";
import { messageOf } from "../services/analysis";
import { useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings } from "../state/settingsStore";

/** レポートの生成中に提示する段。 */
const REPORT_RUN_STAGES = ["資産の走査結果と、指摘・SQL指摘の再検出を1つの文書へ束ねています"];

/** 未生成のときの状態。参照を固定して効果の依存を安定させる。 */
const IDLE_REPORT: ReportState = { status: "idle" };

/**
 * レポートのタブ。engine の `report` を起動して HTML とテキストを書き出し、書いたものを読んで
 * 表示する。レポートの本文は画面で組み立てない。
 *
 * HTML は sandbox 付き iframe の srcdoc で示し、スクリプトを実行させない。形式の切替は engine が
 * 同時に書いた2つの成果物のどちらを見るかの選択であり、engine の再実行を伴わない。
 */
export function ReportTab(): ReactElement {
  const project = useProject();
  const dispatch = useProjectDispatch();
  const settings = useSettings();
  const { inputDir, dbPath } = project;

  const defaultDir = useMemo(() => reportArtifactPaths(dbPath).dir, [dbPath]);
  const [outDir, setOutDir] = useState("");
  const [format, setFormat] = useState<ReportFormat>("HTML");
  const [report, setReport] = useState<ReportState>(IDLE_REPORT);

  const targetDir = outDir === "" ? defaultDir : outDir;
  const disabledCount = useMemo(() => disabledRuleIds(project.catalog).length, [project.catalog]);

  /** report を起動して HTML とテキストを書き、書いたものを読んで示す。 */
  async function writeReport(): Promise<void> {
    if (inputDir === null) return;
    const paths = reportArtifactPaths(dbPath, targetDir);
    setReport({ status: "loading" });
    try {
      // ルールの有効・無効は engine が読む設定ファイルが決める。位置は main が決める。
      const outputPaths = await window.cobolInsight.getOutputPaths();
      const result = await window.cobolInsight.runReport({
        inputDir,
        copybookPaths: [...settings.copybookPaths],
        db: paths.db,
        htmlFile: paths.html,
        textFile: paths.text,
        ruleConfigFile: outputPaths.ruleConfig,
        userRulesFile: outputPaths.userRules,
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
      dispatch({ type: "LOG", text: writeNotice(paths) });
    } catch (error) {
      setReport({ status: "error", message: messageOf(error) });
    }
  }

  /** 書き出しの操作ができない状態でも、タブの見出しは出す。 */
  function placeholder(children: ReactNode): ReactElement {
    return (
      <div className="ci-report ci-report--placeholder">
        <h3 className="ci-report__title">レポート</h3>
        {children}
      </div>
    );
  }

  const view = deriveReportView(project.mode, inputDir, report);
  if (view.kind === "empty") {
    return placeholder(
      <EmptyState
        title="書き出せる解析結果がありません"
        description="資産フォルダを解析すると、呼出関係のまとめ・指摘一覧・SQL指摘をレポートとして書き出せます。"
      />,
    );
  }
  if (view.kind === "running") {
    return placeholder(
      <RunningIndicator title="レポートを生成しています" stages={REPORT_RUN_STAGES} />,
    );
  }
  if (view.kind === "no-project") {
    return placeholder(
      <EmptyState
        title="資産フォルダを選んでいません"
        description="エクスプローラーで資産フォルダを選ぶと、レポートを書き出せます。"
      />,
    );
  }

  const busy = report.status === "loading";
  const failure = view.kind === "error" ? view.message : null;
  const warning = report.status === "ready" ? exitCodeWarning(report.summary.exitCode) : null;

  return (
    <div className="ci-report">
      <ReportForm
        format={format}
        onFormatChange={setFormat}
        outDir={targetDir}
        onOutDirChange={setOutDir}
        disabledRuleCount={disabledCount}
        onWrite={() => void writeReport()}
        busy={busy}
        error={failure === null ? null : `レポートを書き出せませんでした。${failure}`}
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
            title="レポートはまだ書き出していません"
            description="出力先フォルダを確かめて「レポートを書き出す」を押すと、解析エンジンが HTML とテキストを書き出し、その内容をここへ表示します。"
          />
        )}
      </div>
    </div>
  );
}
