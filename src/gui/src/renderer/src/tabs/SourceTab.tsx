import { useMemo, type ReactElement } from "react";
import { CodePane } from "../screens/viewer/CodePane";
import { useSourceDocument } from "../screens/viewer/useSourceDocument";
import {
  COBOL_RULERS,
  findingLinesOf,
  identificationRanges,
  type FindingLine,
} from "../screens/viewer/viewerModel";
import { sourceCodepageOf } from "../screens/viewer/columns";
import { COBOL_LANGUAGE_ID } from "../screens/viewer/cobolMonarch";
import { artifactItems, useProject } from "../state/projectStore";
import { EmptyState } from "../components/EmptyState";

/** 相互ハイライトは対訳を組む回で入る。今は空で固定し、参照を安定させる。 */
const NO_LINES: readonly number[] = [];

export interface SourceTabProps {
  /** 資産の相対パス。 */
  path: string;
  /** 開いた直後に見せる行。 */
  line: number | null;
  /** カーソル位置が変わったときに呼ぶ。ステータスバーが受け取る。 */
  onCursor: (line: number, column: number) => void;
}

/**
 * 資産1件を読み取り専用で見せるタブ。編集と保存、逐語対訳の分割は後の回で足す。
 *
 * 本文の復号は main が担い、この面は固定形式の桁ルーラ・識別欄の弱め・指摘の記号だけを重ねる。
 */
export function SourceTab({ path, line, onCursor }: SourceTabProps): ReactElement {
  const project = useProject();
  const item = useMemo(
    () => artifactItems(project.inventory).find((candidate) => candidate.path === path) ?? null,
    [project.inventory, path],
  );
  const document = useSourceDocument(true, project.inputDir, path, item?.codepage ?? null);

  const findings: readonly FindingLine[] = useMemo(() => {
    if (!project.catalog.loaded) return [];
    return findingLinesOf(
      project.catalog,
      [...artifactItems(project.findings), ...artifactItems(project.sqlAdvice)],
      path,
    );
  }, [project.catalog, project.findings, project.sqlAdvice, path]);

  const identification = useMemo(() => {
    if (document.status !== "ready") return [];
    return identificationRanges(document.text, sourceCodepageOf(document.codepage));
  }, [document]);

  if (document.status === "idle" || document.status === "loading") {
    return <p className="ci-tabbody__note">{path} を読み込んでいます。</p>;
  }

  if (document.status === "unsupported") {
    return (
      <EmptyState
        icon="⚠"
        title="この文字コードは画面で表示できません"
        description={`${path} は ${document.codepage} と判定されています。EBCDIC の資産は解析エンジンが読み取り、画面での表示と編集はこの版では扱いません。`}
      />
    );
  }

  if (document.status === "error") {
    return (
      <div className="ci-banner ci-banner--error" role="alert">
        {path} を読めませんでした。{document.message}
      </div>
    );
  }

  return (
    <div className="ci-tabbody ci-tabbody--code">
      {document.truncated ? (
        <p className="ci-tabbody__note">先頭の一部だけを表示しています。</p>
      ) : null}
      <CodePane
        languageId={COBOL_LANGUAGE_ID}
        text={document.text}
        rulers={COBOL_RULERS}
        linkedLines={NO_LINES}
        notedLines={NO_LINES}
        focusLine={line}
        identification={identification}
        findings={findings}
        glyphMargin
        onCursorLine={onCursor}
        ariaLabel={`${path} の本文`}
      />
    </div>
  );
}
