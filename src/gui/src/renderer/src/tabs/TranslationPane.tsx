import type { ReactElement } from "react";
import type {
  TranspileGeneratedFile,
  TranspileLanguage,
} from "../../../shared/engine-api";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { CodePane } from "../screens/viewer/CodePane";
import { GENERATED_LANGUAGE_ID } from "../vendor/monacoLanguages";
import { TranslationNotes } from "../screens/viewer/TranslationNotes";
import type { TranslationNote } from "../screens/viewer/lineMapIndex";
import {
  LANGUAGE_TABS,
  transpileOutDir,
  type SourceLang,
  type TranspileState,
} from "../screens/viewer/viewerModel";

export interface TranslationPaneProps {
  /** 選んでいる生成言語。 */
  lang: SourceLang;
  onLangChange: (lang: SourceLang) => void;
  /** engine の生成言語(lang から写した値)。 */
  language: TranspileLanguage;
  /** 対訳の取得状態。 */
  transpile: TranspileState;
  /** この資産が対訳の対象(COBOL 本体)か。 */
  target: boolean;
  /** プロジェクトファイル。未解析は null。 */
  dbPath: string | null;
  /** 表示する生成物。選べる生成物が無いときは null。 */
  generated: TranspileGeneratedFile | null;
  /** 選んだ言語の生成物。2件以上あるとき選択欄を出す。 */
  languageFiles: readonly TranspileGeneratedFile[];
  onGeneratedNameChange: (name: string) => void;
  /** 生成物のある言語。片方だけのときの案内に使う。 */
  generatedLanguages: readonly TranspileLanguage[];
  /** 行の対応の件数。対応表はあるが生成物が無い状態を見分けるために使う。 */
  lineMapCount: number;
  /** 相互ハイライトで強調する生成側の行。 */
  linkedLines: readonly number[];
  /** 直訳できなかった注記を持つ生成側の行。 */
  notedLines: readonly number[];
  /** 中央へスクロールする生成側の行。 */
  revealLine: number | null;
  onCursorLine: (line: number) => void;
  notes: readonly TranslationNote[];
  onSelectNote: (note: TranslationNote) => void;
  onRegenerate: () => void;
}

/**
 * 逐語対訳のペイン。engine が生成した対訳を出し、行の対応で原本と相互にハイライトする。
 * 生成も対応表も engine が供給源であり、この面は表示と、作り直しの起動だけを持つ。
 */
export function TranslationPane({
  lang,
  onLangChange,
  language,
  transpile,
  target,
  dbPath,
  generated,
  languageFiles,
  onGeneratedNameChange,
  generatedLanguages,
  lineMapCount,
  linkedLines,
  notedLines,
  revealLine,
  onCursorLine,
  notes,
  onSelectNote,
  onRegenerate,
}: TranslationPaneProps): ReactElement {
  return (
    <section className="ci-viewer__pane ci-viewer__pane--translation" aria-label="逐語対訳">
      <header className="ci-viewer__pane-head">
        <h3 className="ci-viewer__pane-title">逐語対訳</h3>
        {LANGUAGE_TABS.map((tab) => (
          <Button
            key={tab.lang}
            variant={lang === tab.lang ? "primary" : "default"}
            aria-pressed={lang === tab.lang}
            onClick={() => onLangChange(tab.lang)}
          >
            {tab.label}
          </Button>
        ))}
        {languageFiles.length > 1 ? (
          <select
            className="ci-viewer__gen-select"
            aria-label="表示する生成物"
            value={generated?.name ?? ""}
            onChange={(event) => onGeneratedNameChange(event.target.value)}
          >
            {languageFiles.map((file) => (
              <option key={file.name} value={file.name}>
                {file.name}
              </option>
            ))}
          </select>
        ) : null}
        <span className="ci-viewer__pane-spacer" />
        <span className="ci-viewer__pane-meta">カーソル行で相互にハイライトします</span>
      </header>
      <div className="ci-viewer__pane-body">
        {!target ? (
          <EmptyState
            title="この資産は逐語対訳の対象ではありません"
            description="逐語対訳は COBOL 本体に対して生成します。JCL・コピー句・BMS マップは対訳を持ちません。"
          />
        ) : dbPath === null ? (
          <EmptyState
            title="解析結果のプロジェクトファイルがありません"
            description="エクスプローラーの「再解析」を実行すると、対訳の対応表を持つプロジェクトファイルができます。"
          />
        ) : transpile.status === "loading" ? (
          <p className="ci-viewer__loading" role="status">
            逐語対訳を生成・読込しています…
          </p>
        ) : transpile.status === "error" ? (
          <EmptyState
            icon="！"
            title="逐語対訳を取得できませんでした"
            description={`${transpile.message} 解析を実行し直すと、対訳を作り直せます。`}
          />
        ) : generated === null && generatedLanguages.length > 0 ? (
          <EmptyState
            title="この言語の生成物がありません"
            description="選んだ言語の生成物がありません。もう一方の言語へ切り替えると表示できます。"
          />
        ) : generated === null && lineMapCount > 0 ? (
          <EmptyState
            icon="！"
            title="生成物が出力先に見つかりません"
            description={`行の対応表はありますが、対応する生成物が出力先 ${transpileOutDir(dbPath)} にありません。別の出力先で生成した対応表がプロジェクトファイルに残っています。`}
            actionLabel="逐語対訳を作り直す"
            onAction={onRegenerate}
          />
        ) : generated === null ? (
          <EmptyState
            title="逐語対訳が生成されていません"
            description="この資産の対訳は生成されていません。行の対応表に対応がないため、対訳を表示できません。"
            actionLabel="逐語対訳を作り直す"
            onAction={onRegenerate}
          />
        ) : (
          <CodePane
            languageId={GENERATED_LANGUAGE_ID[language]}
            text={generated.text}
            linkedLines={linkedLines}
            notedLines={notedLines}
            focusLine={null}
            revealLine={revealLine}
            onCursorLine={(line) => onCursorLine(line)}
            ariaLabel={`逐語対訳 ${generated.name}`}
          />
        )}
      </div>
      <TranslationNotes notes={notes} onSelect={onSelectNote} />
    </section>
  );
}
