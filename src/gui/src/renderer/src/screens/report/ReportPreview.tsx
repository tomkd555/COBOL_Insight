import type { ReactElement } from "react";
import type { ReportFormat } from "../../state/appState";
import { REPORT_SANDBOX, reportMetrics, type ReportSummary } from "./reportModel";

export interface ReportPreviewProps {
  format: ReportFormat;
  /** engine が書いた HTML レポートの本文。 */
  html: string;
  /** engine が書いたテキストレポートの本文。 */
  text: string;
  summary: ReportSummary;
  /** 表示中のファイルのパス(利用者へ書出先を示す)。 */
  path: string;
}

/**
 * レポートのプレビュー。engine が書いた本文をそのまま表示する。
 *
 * HTML は sandbox 付き iframe の srcdoc へ入れる。sandbox を空値にすることでスクリプト実行・
 * フォーム送信・同一オリジン扱いのすべてを落とし、レポート HTML から renderer の DOM・ストレージへ
 * 触れる経路を断つ。テキストは等幅で桁をそろえて示す。
 */
export function ReportPreview({ format, html, text, summary, path }: ReportPreviewProps): ReactElement {
  return (
    <div className="ci-report-preview">
      <p className="ci-report-preview__path">{`プレビュー ― ${path}`}</p>
      <dl className="ci-report-preview__metrics">
        {reportMetrics(summary).map((metric) => (
          <div key={metric.label} className="ci-report-preview__metric">
            <dt className="ci-report-preview__metric-label">{metric.label}</dt>
            <dd className="ci-report-preview__metric-value">{metric.value}</dd>
          </div>
        ))}
      </dl>
      {format === "HTML" ? (
        <iframe
          className="ci-report-preview__frame"
          title="HTML レポートのプレビュー"
          sandbox={REPORT_SANDBOX}
          srcDoc={html}
        />
      ) : (
        <pre className="ci-report-preview__text" aria-label="テキストレポートのプレビュー">
          {text}
        </pre>
      )}
    </div>
  );
}
