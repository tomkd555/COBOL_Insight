/**
 * ソースビューアのビューモデル導出(React 非依存の純ロジック)。design gvViewer の
 * ファイル選択・言語切替・相互ハイライトの提示を移植する。
 *
 * 本文は main の readSourceText(表示専用の復号)、対訳は translate の生成物と LINE_MAP が供給源で
 * あり、GUI は解析も復号も行わない。固定形式の欄割りのうち識別欄(73〜80桁)は Monarch では
 * 桁位置を条件にできないため、ここで範囲を求めて装飾として示す。
 */

import {
  COPY_EXPANSION_FILE_NAME,
  type AssetInventoryItem,
  type CopyExpansion,
  type CopyExpansionData,
  type LineMapEntry,
  type SarifFinding,
  type SourceTextResult,
  type TranspileGeneratedFile,
  type TranspileLanguage,
} from "../../../../shared/engine-api";
import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../../components/severity";
import { ruleOf, type RuleCatalogIndex } from "../../data/ruleCatalog";
import { charIndexAfterBytes, type SourceCodepage } from "./columns";
import type { CopyStatement } from "./copybookLookup";

/** 逐語対訳の生成言語の選択。 */
export type SourceLang = "py" | "java";

/** 本体(8〜72桁)の終端桁。識別欄はこの次の桁から始まる。 */
export const BODY_LAST_COLUMN = 72;

/** 桁ルーラの位置。一連番号欄の終端・標識欄の終端・A領域とB領域の境界・本体の終端・行の終端。 */
export const COBOL_RULERS: readonly number[] = [6, 7, 11, BODY_LAST_COLUMN, 80];

/** ソース本文の取得状態。復号非対応と読取失敗を、本文が空であることと区別して保つ。 */
export type DocumentState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly text: string; readonly codepage: string; readonly truncated: boolean }
  | { readonly status: "unsupported"; readonly codepage: string }
  | { readonly status: "error"; readonly message: string };

/** 逐語対訳の取得状態。生成物と行対応表を1つの成果物として扱う。 */
export type TranspileState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly files: readonly TranspileGeneratedFile[];
      readonly lineMap: readonly LineMapEntry[];
    }
  | { readonly status: "error"; readonly message: string };

/**
 * COPY 展開の取得状態。展開は scan の対応表が供給源であり、copybookLines は注記行を補うために
 * 読んだ原本のコピー句(相対パス→行)である。原本を読めなかったコピー句は copybookLines に載らない。
 */
export type CopyExpansionState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly expansions: readonly CopyExpansion[];
      readonly copybookLines: ReadonlyMap<string, readonly string[]>;
    }
  | { readonly status: "error"; readonly message: string };

/** readSourceText の結果を取得状態へ写す。本文は Monaco へ載せるため原文のまま保つ。 */
export function toDocument(result: SourceTextResult): DocumentState {
  if (result.unsupported) {
    return { status: "unsupported", codepage: result.codepage };
  }
  return { status: "ready", text: result.text, codepage: result.codepage, truncated: result.truncated };
}

/** パスの属するフォルダ(末尾の区切りを含む)。フォルダを持たない相対名では空文字。 */
function directoryOf(path: string): string {
  const separator = Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/"));
  return separator < 0 ? "" : path.slice(0, separator + 1);
}

/** translate の出力先。解析で使う SQLite と同じフォルダの transpile へそろえる。 */
export function transpileOutDir(dbPath: string): string {
  return `${directoryOf(dbPath)}transpile`;
}

/**
 * COPY 展開の対応表の位置。scan は engine の既定の出力先(SQLite と同じ場所)へ書くため
 * (main の args.ts)、プロジェクトファイルの位置から同じパスを指せる。
 */
export function copyExpansionFile(dbPath: string): string {
  return `${directoryOf(dbPath)}${COPY_EXPANSION_FILE_NAME}`;
}

/** 画面の言語切替(design lang)を engine の生成言語へ写す。 */
export function generatedLanguageOf(lang: SourceLang): TranspileLanguage {
  return lang === "py" ? "python" : "java";
}

/** 言語切替ボタンの並びと表示名。 */
export interface LanguageTab {
  readonly lang: SourceLang;
  readonly label: string;
}

export const LANGUAGE_TABS: readonly LanguageTab[] = [
  { lang: "py", label: "Python" },
  { lang: "java", label: "Java" },
];

/** 指定した言語の生成物を名前順に返す。1つの COBOL 本体から複数の生成物が出ることがある。 */
export function generatedFilesFor(
  files: readonly TranspileGeneratedFile[],
  language: TranspileLanguage,
): TranspileGeneratedFile[] {
  return files
    .filter((file) => file.language === language)
    .slice()
    .sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));
}

/**
 * 表示する生成物を決める。指定した名前がその言語の生成物にあればそれを、無ければ先頭を選ぶ。
 * 言語を切り替えた直後は前の言語の名前が残るため、後者の経路で先頭へ戻す。
 */
export function selectGeneratedFile(
  files: readonly TranspileGeneratedFile[],
  language: TranspileLanguage,
  preferredName: string | null,
): TranspileGeneratedFile | null {
  const candidates = generatedFilesFor(files, language);
  if (candidates.length === 0) {
    return null;
  }
  return candidates.find((file) => file.name === preferredName) ?? candidates[0];
}

/** 生成された言語を Python → Java の順で列挙する。 */
export function availableGeneratedLanguages(
  files: readonly TranspileGeneratedFile[],
): TranspileLanguage[] {
  const order: readonly TranspileLanguage[] = ["python", "java"];
  return order.filter((language) => files.some((file) => file.language === language));
}

/** 行内の桁範囲(Monaco の桁は 1 起点で、終端は最後の文字の次の桁を指す)。 */
export interface LineColumnRange {
  readonly line: number;
  readonly startColumn: number;
  readonly endColumn: number;
}

/**
 * 識別欄(73〜80桁)の範囲。72桁を超えて書かれた部分は原始プログラムの本体ではないため、
 * 本体と区別して示す。Monarch は桁位置を条件にできないため、装飾としてここで求める。
 * 桁はバイトで数えるため、DBCS を含む行では文字数と桁が一致しない(columns.ts)。
 */
export function identificationRanges(text: string, codepage: SourceCodepage): LineColumnRange[] {
  if (text === "") {
    return [];
  }
  const ranges: LineColumnRange[] = [];
  text.split(/\r\n|\n|\r/).forEach((line, index) => {
    const bodyEnd = charIndexAfterBytes(line, BODY_LAST_COLUMN, codepage);
    if (bodyEnd < line.length) {
      ranges.push({ line: index + 1, startColumn: bodyEnd + 1, endColumn: line.length + 1 });
    }
  });
  return ranges;
}

/** 一連番号欄(1〜6桁)の桁数。 */
const SEQUENCE_COLUMNS = 6;

/** 展開行1行。原本の行番号を持たないため、コピー句の中での行番号を添えて出所を示す。 */
export interface ExpansionZoneLine {
  readonly copybookLine: number;
  readonly text: string;
  /** 前処理で空になった注記行を、原本のコピー句から補ったか。 */
  readonly restored: boolean;
}

/** COPY 文1件ぶんの差し込み。afterLine の直後へ lines を並べる。 */
export interface ExpansionZone {
  /** 差し込む位置(原本の COPY 文の行。1 起点)。 */
  readonly afterLine: number;
  /** 見出し(コピー句名・取り込み元・行数)。 */
  readonly title: string;
  /** 見出しに添える、欄の扱いまたは展開できない理由。 */
  readonly note: string;
  readonly ariaLabel: string;
  /** 展開データが無い COPY 文では空。 */
  readonly lines: readonly ExpansionZoneLine[];
}

/** 差し込みを組む入力。 */
export interface ExpansionZoneInput {
  /** 原本から検出した COPY 文。展開データが無い COPY 文を見落とさないために要る。 */
  readonly statements: readonly CopyStatement[];
  /** scan の対応表のうち、表示中の資産の分。 */
  readonly expansions: readonly CopyExpansion[];
  /** 注記行を補うための原本のコピー句(相対パス→行)。 */
  readonly copybookLines: ReadonlyMap<string, readonly string[]>;
  /** 一連番号欄を桁で数えるための文字集合。 */
  readonly codepage: SourceCodepage;
}

/** 対応表から、表示中の資産の展開を COPY 文の行番号順で取り出す。 */
export function expansionsFor(data: CopyExpansionData, path: string): CopyExpansion[] {
  const program = data.programs.find((entry) => entry.path === path);
  if (program === undefined) {
    return [];
  }
  return program.expansions
    .slice()
    .sort((left, right) => left.copyStatementLine - right.copyStatementLine);
}

/** 一連番号欄を空白に置き換える。展開行どうしで 1〜6桁の見え方をそろえる。 */
function blankSequenceArea(line: string, codepage: SourceCodepage): string {
  return `${" ".repeat(SEQUENCE_COLUMNS)}${line.slice(charIndexAfterBytes(line, SEQUENCE_COLUMNS, codepage))}`;
}

/**
 * 展開行1行を組む。engine の前処理は注記行を空白にするため、原本のコピー句を読めている場合は
 * その行で補う(注記は REPLACING の対象ではないため、補っても展開後の姿と食い違わない)。
 */
function toZoneLine(
  copybookLine: number,
  text: string,
  original: readonly string[] | undefined,
  codepage: SourceCodepage,
): ExpansionZoneLine {
  if (text.trim() !== "" || original === undefined) {
    return { copybookLine, text, restored: false };
  }
  const source = original[copybookLine - 1];
  if (source === undefined || source.trim() === "") {
    return { copybookLine, text, restored: false };
  }
  return { copybookLine, text: blankSequenceArea(source, codepage), restored: true };
}

/**
 * COPY 文の位置へ差し込む展開を組む。原本の行番号は展開行に無いため、差し込みは原本の行の並びを
 * 崩さない付加物として扱い、出所(コピー句名とコピー句の中での行番号)を各行へ添える。
 * 展開データが無い COPY 文(入れ子の COPY・暗黙のコピー句)は、無いことが判るよう理由だけを差し込む。
 */
export function buildExpansionZones(input: ExpansionZoneInput): ExpansionZone[] {
  const byLine = new Map(input.expansions.map((expansion) => [expansion.copyStatementLine, expansion]));
  const zones: ExpansionZone[] = [];
  for (const statement of input.statements) {
    const expansion = byLine.get(statement.line);
    if (expansion === undefined) {
      zones.push({
        afterLine: statement.line,
        title: `COPY ${statement.name} ― 展開データがありません`,
        note: "入れ子の COPY と暗黙のコピー句(SQLCA)は展開の対象外です。",
        ariaLabel: `${statement.line} 行の COPY ${statement.name} は展開データがありません`,
        lines: [],
      });
      continue;
    }
    const original = input.copybookLines.get(expansion.copybookPath);
    zones.push({
      afterLine: statement.line,
      title: `COPY ${expansion.copybookName} の展開 ― ${expansion.copybookPath}（${expansion.lines.length} 行）`,
      note:
        original === undefined
          ? "REPLACING 適用後。一連番号欄・識別欄と注記行は前処理で空になるため、注記行は空のまま示します。"
          : "REPLACING 適用後。一連番号欄・識別欄は前処理で空になり、注記行は原本から補います。",
      ariaLabel: `${statement.line} 行の COPY ${expansion.copybookName} の展開 ${expansion.lines.length} 行`,
      lines: expansion.lines.map((line) =>
        toZoneLine(line.copybookLine, line.text, original, input.codepage),
      ),
    });
  }
  return zones.sort((left, right) => left.afterLine - right.afterLine);
}

/** COPY 展開の状態を1行で示す。件数と、展開できていない分の有無を分けて示す。 */
export function copyExpansionSummary(
  statements: readonly CopyStatement[],
  state: CopyExpansionState,
): string {
  if (state.status === "loading") {
    return "コピー句の展開を読み込んでいます…";
  }
  if (state.status === "error") {
    return `コピー句の展開を取得できませんでした（${state.message}）。解析を実行し直すと対応表を作り直します。`;
  }
  if (state.status === "idle") {
    return `COPY 文 ${statements.length} 件`;
  }
  const lines = new Set(state.expansions.map((expansion) => expansion.copyStatementLine));
  const expanded = statements.filter((statement) => lines.has(statement.line)).length;
  const missing = statements.length - expanded;
  const head = `COPY 文 ${statements.length} 件のうち ${expanded} 件を展開中`;
  return missing === 0 ? head : `${head}（${missing} 件は展開データがありません）`;
}

/** 1行に載る指摘1件。重大度と名称は SARIF の level ではなくルールカタログから引く。 */
export interface FindingEntry {
  readonly ruleId: string;
  readonly ruleName: string;
  readonly severity: Severity;
  /** 指摘の根拠(SARIF の message)。 */
  readonly message: string;
}

/** 指摘のある行1つ。重大度は同じ行の指摘のうち最も重いものを採る。 */
export interface FindingLine {
  readonly line: number;
  readonly severity: Severity;
  readonly count: number;
  /** 重い順・同じ重大度ならルール ID 順の内訳。 */
  readonly entries: readonly FindingEntry[];
}

/** 重い順・同じ重大度ならルール ID 順。 */
function compareEntries(left: FindingEntry, right: FindingEntry): number {
  const rank = SEVERITY_ORDER.indexOf(left.severity) - SEVERITY_ORDER.indexOf(right.severity);
  if (rank !== 0) {
    return rank;
  }
  return left.ruleId < right.ruleId ? -1 : left.ruleId > right.ruleId ? 1 : 0;
}

/** 表示中のファイルに属する指摘を行番号ごとにまとめ、行番号昇順で返す。 */
export function findingLinesOf(
  catalog: RuleCatalogIndex,
  findings: readonly SarifFinding[],
  file: string,
): FindingLine[] {
  const byLine = new Map<number, FindingEntry[]>();
  for (const finding of findings) {
    if (finding.file !== file) {
      continue;
    }
    const rule = ruleOf(catalog, finding.ruleId);
    const entries = byLine.get(finding.startLine) ?? [];
    entries.push({
      ruleId: finding.ruleId,
      ruleName: rule.name,
      severity: rule.severity,
      message: finding.message,
    });
    byLine.set(finding.startLine, entries);
  }
  return [...byLine]
    .map(([line, entries]) => {
      const sorted = entries.slice().sort(compareEntries);
      return { line, severity: sorted[0].severity, count: sorted.length, entries: sorted };
    })
    .sort((left, right) => left.line - right.line);
}

/**
 * 指摘行のホバー本文(Markdown)。件数と最も重い重大度を見出しに置き、内訳を
 * 「記号 重大度 ルール ID ルール名」と根拠の2行で並べる。記号を添えて色に頼らずに読めるようにする。
 */
export function findingHoverText(line: FindingLine): string {
  const head = `**この行の指摘 ${line.count} 件**（最も重い重大度: ${SEVERITY_META[line.severity].label}）`;
  const body = line.entries.map((entry) => {
    const meta = SEVERITY_META[entry.severity];
    return `- ${meta.symbol} ${meta.label} \`${entry.ruleId}\` ${entry.ruleName}\n\n  ${entry.message}`;
  });
  return [head, ...body].join("\n\n");
}

/** Monaco へ渡す装飾に使う CSS クラス。monaco が自身の行要素へ付けるため、単独で意味を持たせる。 */
export const LINE_CLASS = {
  linked: "ci-code__line--linked",
  focus: "ci-code__line--focus",
  noted: "ci-code__line--noted",
  identification: "ci-code__identification",
} as const;

/** 指摘行の地色に使うクラス。重大度で色を分ける。 */
export function findingLineClass(severity: Severity): string {
  return `ci-code__line--finding-${severity}`;
}

/** グリフ余白へ出す重大度の記号のクラス。記号は擬似要素で描き、余白の左端に色帯を兼ねる。 */
export function findingGlyphClass(severity: Severity): string {
  return `ci-code__glyph ci-code__glyph--${severity}`;
}

/** 行末へ添える件数のクラス。 */
function findingCountClass(severity: Severity): string {
  return `ci-code__finding-count ci-code__finding-count--${severity}`;
}

/**
 * 行末を指す桁。Monaco は装飾の範囲を行の実長へ丸めるため、行の長さを知らずに行全体を指せる
 * (Monaco が桁に使う最大値)。
 */
export const LINE_END_COLUMN = 1073741823;

/** Monaco のホバー本文(IMarkdownString の部分集合)。 */
export interface HoverText {
  readonly value: string;
}

/** Monaco の装飾1件(IModelDeltaDecoration の部分集合)。 */
export interface EditorDecoration {
  range: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number };
  options: {
    isWholeLine?: boolean;
    className?: string;
    inlineClassName?: string;
    glyphMarginClassName?: string;
    hoverMessage?: HoverText;
    glyphMarginHoverMessage?: HoverText;
    after?: { content: string; inlineClassName: string };
  };
}

/** Monaco のマーカー1件(IMarkerData の部分集合)。誤りのある行を波線と一覧で示す。 */
export interface EditorMarker {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
  message: string;
  /** monaco.MarkerSeverity の値。 */
  severity: number;
}

/**
 * monaco.MarkerSeverity.Error の値。列挙を引くには Monaco の読み込みが要り、マーカーを組む側を
 * 純関数として試験できなくなるため、値をここに置く。
 */
export const MARKER_SEVERITY_ERROR = 8;

/** マーカーの所有者。同じ資産に別の由来のマーカーを付けても取り違えないための名前である。 */
export const SAVE_MARKER_OWNER = "cobol-insight-save";

/** 装飾の入力。指摘行・強調行・注記行・ジャンプ先の行・識別欄の範囲を渡す。 */
export interface DecorationInput {
  readonly linkedLines: readonly number[];
  readonly notedLines: readonly number[];
  readonly focusLine: number | null;
  readonly identification: readonly LineColumnRange[];
  readonly findings: readonly FindingLine[];
}

/** 行単位の装飾を組む。 */
function wholeLine(line: number, className: string): EditorDecoration {
  return {
    range: { startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 },
    options: { isWholeLine: true, className },
  };
}

/**
 * ペインへ渡す装飾を組む。並びは指摘行 → 注記行 → 対応行 → ジャンプ先の行 → 識別欄とし、
 * 後に置いた装飾を上へ重ねる(ジャンプ先の行を対応行より強く示す)。指摘行を最下層へ敷くのは、
 * 指摘の在り処を行の地色ではなくグリフ余白の記号と色帯で示し、ジャンプ先の強調と場所を分けるためである。
 */
export function buildLineDecorations(input: DecorationInput): EditorDecoration[] {
  const decorations: EditorDecoration[] = [];
  for (const line of input.findings) {
    const hover: HoverText = { value: findingHoverText(line) };
    const decoration: EditorDecoration = {
      range: {
        startLineNumber: line.line,
        startColumn: 1,
        endLineNumber: line.line,
        endColumn: LINE_END_COLUMN,
      },
      options: {
        isWholeLine: true,
        className: findingLineClass(line.severity),
        glyphMarginClassName: findingGlyphClass(line.severity),
        hoverMessage: hover,
        glyphMarginHoverMessage: hover,
      },
    };
    if (line.count > 1) {
      decoration.options.after = {
        content: ` ${SEVERITY_META[line.severity].symbol}${line.count}`,
        inlineClassName: findingCountClass(line.severity),
      };
    }
    decorations.push(decoration);
  }
  for (const line of input.notedLines) {
    decorations.push(wholeLine(line, LINE_CLASS.noted));
  }
  for (const line of input.linkedLines) {
    decorations.push(wholeLine(line, LINE_CLASS.linked));
  }
  if (input.focusLine !== null) {
    decorations.push(wholeLine(input.focusLine, LINE_CLASS.focus));
  }
  for (const range of input.identification) {
    decorations.push({
      range: {
        startLineNumber: range.line,
        startColumn: range.startColumn,
        endLineNumber: range.line,
        endColumn: range.endColumn,
      },
      options: { inlineClassName: LINE_CLASS.identification },
    });
  }
  return decorations;
}

/**
 * Monaco の実測寸法。桁見出しをコード面の桁位置へそろえるために用いる。行番号ガターの幅も
 * 文字送りも Monaco が実測して決めるため、見出し側で固定幅を組むと桁位置とずれる。
 */
export interface EditorMetrics {
  /** コード面の左端(グリフ余白と行番号ガターを含む)の画素位置。 */
  readonly contentLeft: number;
  /** 半角1文字の送り幅(画素)。 */
  readonly charWidth: number;
  /** 水平スクロール量(画素)。 */
  readonly scrollLeft: number;
}

/** 実測寸法が同じか。同じあいだは見出しを描き直さない。 */
export function metricsEqual(left: EditorMetrics | null, right: EditorMetrics): boolean {
  return (
    left !== null &&
    left.contentLeft === right.contentLeft &&
    left.charWidth === right.charWidth &&
    left.scrollLeft === right.scrollLeft
  );
}

/** 桁(1起点)の左端の画素位置。 */
export function columnLeftPx(metrics: EditorMetrics, column: number): number {
  return metrics.contentLeft + (column - 1) * metrics.charWidth - metrics.scrollLeft;
}

/**
 * 桁見出しのラベル1つ。桁境界へ寄せて置き、境界そのものは端の罫線で示す。
 * anchor=start は境界から右へ、end は境界から左へラベルを伸ばす。
 */
export interface ColumnMark {
  /** BEM 修飾子。 */
  readonly name: string;
  /** 寄せる桁(1起点)。この桁の左端が境界になる。 */
  readonly column: number;
  readonly anchor: "start" | "end";
  readonly label: string;
}

/**
 * 桁見出しのラベル。境界は本体の始まり(8桁目)と識別欄の始まり(73桁目)の2つに絞る。
 * 一連番号欄と標識欄は幅が 6桁・1桁 しかなく、名称を桁幅に収められないため、
 * 本体の左の境界へ寄せて1つのラベルにまとめる。
 */
export const COLUMN_MARKS: readonly ColumnMark[] = [
  { name: "head", column: 8, anchor: "end", label: "1-6 一連番号 / 7 標識" },
  { name: "body", column: 8, anchor: "start", label: "8 本体（A/B 領域）" },
  { name: "body-end", column: 73, anchor: "end", label: "72" },
  { name: "identification", column: 73, anchor: "start", label: "73-80 識別欄" },
];

/** 逐語対訳の対象か。対訳は COBOL 本体(NODE.type=PROGRAM)に対してのみ生成される。 */
export function isTranspileTarget(item: AssetInventoryItem | null): boolean {
  return item !== null && item.type === "PROGRAM";
}

/**
 * 相互ハイライトの状態を1行で示す。強調する行が片方も無い場合は、対応表に無い行である旨を示す
 * (対応が空であることを、対応の取得前と同じ文言で示す)。
 */
export function linkSummary(
  cobolLines: readonly number[],
  generatedLines: readonly number[],
): string {
  if (cobolLines.length === 0 && generatedLines.length === 0) {
    return "カーソル行に対応する行はありません";
  }
  return `対応行を強調中 ― COBOL ${cobolLines.length} 行 ↔ 生成 ${generatedLines.length} 行`;
}
