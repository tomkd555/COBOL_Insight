import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";
import type { ReportFormat } from "../../state/appState";

/** 出力形式の選択肢。engine は1回の実行で両形式を書くため、これは表示の切替である。 */
const FORMATS: readonly ReportFormat[] = ["HTML", "テキスト"];

export interface ReportFormProps {
  format: ReportFormat;
  onFormatChange: (format: ReportFormat) => void;
  /** 出力先フォルダ。engine の --html / --text の親フォルダになる。 */
  outDir: string;
  onOutDirChange: (value: string) => void;
  /** 無効化したルール数(report へ --disable-rule として渡す件数)。 */
  disabledRuleCount: number;
  onWrite: () => void;
  /** レポートの生成中はボタンを押させない。 */
  busy: boolean;
  /** 生成の失敗理由。null のときはバナーを出さない。 */
  error: string | null;
}

/**
 * レポート出力のフォーム(design scRep の左 400px)。出力形式・出力先フォルダ・書き出しの操作を持つ。
 *
 * 章の取捨は engine の `report` に選択肢が無いため置かない。レポートには呼出関係サマリ・指摘一覧・
 * SQL助言が常に含まれ、1回の実行で HTML とテキストの両方が書かれる。
 */
export function ReportForm({
  format,
  onFormatChange,
  outDir,
  onOutDirChange,
  disabledRuleCount,
  onWrite,
  busy,
  error,
}: ReportFormProps): ReactElement {
  return (
    <div className="ci-report-form">
      <h3 className="ci-report-form__title">レポート出力</h3>
      {error === null ? null : (
        <div className="ci-banner ci-banner--error" role="alert">
          {error}
        </div>
      )}

      <div className="ci-report-form__field">
        <p className="ci-report-form__label">表示する形式</p>
        <div className="ci-report-form__formats" role="group" aria-label="表示する形式">
          {FORMATS.map((option) => (
            <Button
              key={option}
              variant={option === format ? "primary" : "default"}
              aria-pressed={option === format}
              onClick={() => onFormatChange(option)}
            >
              {option}
            </Button>
          ))}
        </div>
        <p className="ci-report-form__note">
          解析エンジンは1回の実行で HTML とテキストの両方を書き出す。ここでの選択は表示の切替である。
        </p>
      </div>

      <div className="ci-report-form__field">
        <p className="ci-report-form__label">出力内容</p>
        <p className="ci-report-form__note">
          {"呼出関係サマリ・指摘一覧・SQL助言を1つの文書へ束ねる。章は選べず、常に全章を出力する。"}
          {disabledRuleCount === 0
            ? ""
            : `設定で無効化した ${disabledRuleCount} 件のルールは検出から除く。`}
        </p>
      </div>

      <div className="ci-report-form__field">
        <label className="ci-report-form__label" htmlFor="ci-report-outdir">
          出力先フォルダ
        </label>
        <TextInput
          id="ci-report-outdir"
          aria-label="出力先フォルダ"
          className="ci-report-form__path"
          value={outDir}
          onChange={(event) => onOutDirChange(event.target.value)}
        />
      </div>

      <Button variant="primary" disabled={busy} onClick={onWrite}>
        {busy ? "レポートを生成している…" : "レポートを書き出す"}
      </Button>
      <p className="ci-report-form__note">
        レポートはローカルへ保存する。ネットワーク送信は行わない。
      </p>
    </div>
  );
}
