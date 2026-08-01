import { useMemo, type CSSProperties, type ReactElement } from "react";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { SPLIT_PANES } from "../../state/appState";
import { Button } from "../../components/Button";
import { CodeFocusButton } from "../../components/CodeFocusButton";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SplitHandle } from "../../components/SplitHandle";
import { SCREEN_META } from "../screenMeta";
import { previewCodepage } from "../explorer/assetView";
import { useSourceDocument } from "../viewer/useSourceDocument";
import { FindingsCode } from "./FindingsCode";
import { FindingsView } from "./FindingsView";
import { nextSortState, type FindingFilters } from "./findingsModel";
import type { AssetInventoryItem, SarifFinding } from "../../../../shared/engine-api";

/** 未取得のときに使う空の資産一覧。参照を固定して useMemo の依存を安定させる。 */
const EMPTY_INVENTORY: readonly AssetInventoryItem[] = [];

/** 資産を選ぶ前の下段に出す案内。 */
const CODE_HINT =
  "一覧の行番号を押すと、この下に該当資産の原本を出して該当行へ移る。行を選ぶだけでも下段が追従するため、矢印キーで指摘を送りながら読める。";

/**
 * 指摘一覧(lint)。表示する指摘は「▶ 解析実行」が起動した lint の SARIF を AppState へ収めたもので、
 * この画面は lint を起動しない(タブを開くたびの再解析が起きない)。フィルタ(重大度/ルール/
 * ファイル/内容)・ソート・選択は共有一覧 UI(FindingsView)へ委ね、いずれも AppState に持つ。
 *
 * 上に一覧、下に COBOL 原本を縦に並べる。指摘を見て該当行を読み、直すかを判断する作業は、一覧と
 * コードが同時に見えて初めて成り立つ。左右に割らないのは、表の内容幅とコード面の 80 桁が 1280px の
 * 幅では並ばないためである。行の選択も行番号のセルもそのまま下段の追従になり(SELECT_FINDING と、
 * 画面に留まる JUMP)、ソースビューアへ移る操作はコード面の見出しへ明示的に置く。表示中の資産と行は
 * 両画面で共有するため、移った先には同じ場所が開く。
 *
 * 空状態は 2 バリアント: mode=empty は「未解析」、解析済みで 0 件は「指摘なし」。lint の起動または
 * SARIF 読取が失敗した場合は 0 件と区別し、失敗として理由を示す。
 */
export function FindingsScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.findings;
  const result = state.findings;
  const inputDir = state.project.inputDir;
  const sourceFile = state.sourceFile;

  // 下段の原本。文字コードは資産一覧の検出値(手動指定があればそれ)を引く。
  const inventory = state.inventory.status === "ready" ? state.inventory.items : EMPTY_INVENTORY;
  const item = useMemo(
    () => inventory.find((entry) => entry.path === sourceFile) ?? null,
    [inventory, sourceFile],
  );
  const codepage =
    item === null ? null : previewCodepage(item, state.encodingSel, state.defaultEncoding);
  const document = useSourceDocument(inputDir !== null, inputDir, sourceFile, codepage);

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="解析がまだ実行されていません"
        description="資産を取り込んで解析を実行すると、31 種の検出ルール（R001〜R031）による指摘を一覧できる。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (state.mode === "running") {
    return <RunningIndicator title={meta.runningTitle} activeStage={state.runStage} />;
  }
  if (result.status === "error") {
    return (
      <EmptyState
        icon="！"
        title="指摘を取得できませんでした"
        description={`指摘の検出の実行または検出結果の読み取りに失敗したため、指摘の件数は分からない。${result.message}`}
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.status === "none") {
    return (
      <EmptyState
        title="指摘をまだ取得していません"
        description="解析実行が完了していないため、指摘を表示できない。資産一覧で解析を実行する。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.items.length === 0) {
    return (
      <EmptyState
        icon="✓"
        title="指摘はありません"
        description="解析は正常に完了し、有効なルールに該当する箇所は検出されなかった。"
      />
    );
  }

  const filters: FindingFilters = {
    severity: state.findingSeverity,
    threshold: state.severityThreshold,
    rule: state.findingRule,
    file: state.findingFile,
    text: state.findingText,
  };

  const listHeight = state.paneWidths.findingsList;
  // 一覧の高さと、コード面へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  const paneStyle = {
    "--ci-findings-list-h": `${listHeight}px`,
    "--ci-opposite-min": `${SPLIT_PANES.findingsList.oppositeMin}px`,
  } as CSSProperties;

  /**
   * 指摘の位置を下段のコードへ出す。行の選択・行番号のセルのいずれもこれを起こし、画面は移らない。
   * 指摘を見て該当行を読む作業は、一覧とコードが同時に見えて初めて成り立つためである。
   */
  const showInCode = (row: { finding: SarifFinding }): void => {
    dispatch({ type: "SELECT_FINDING", finding: row.finding });
    dispatch({
      type: "JUMP",
      file: row.finding.file,
      line: row.finding.startLine,
      from: "指摘一覧",
      stay: true,
    });
  };

  // 畳む一覧の中に置くと最大化した時点で消えるため、常に見えているコード面の見出しへ置く。
  // ソースビューアへ移る操作もここに置く(逐語対訳と突き合わせるのはあちらの役目である)。
  const paneActions = (
    <>
      {sourceFile === "" ? null : (
        <Button
          onClick={() =>
            dispatch({
              type: "JUMP",
              file: sourceFile,
              line: state.sourceLine,
              from: "指摘一覧",
            })
          }
        >
          ソースビューアで開く
        </Button>
      )}
      <CodeFocusButton
        active={state.codeFocus}
        onToggle={() => dispatch({ type: "TOGGLE_CODE_FOCUS" })}
        target="一覧"
      />
    </>
  );

  return (
    <div className="ci-findings-split" style={paneStyle}>
      {state.codeFocus ? null : (
        <>
          <div className="ci-findings-split__list">
            <FindingsView
              findings={result.items}
              filters={filters}
              handlers={{
                onToggleSeverity: (severity) =>
                  dispatch({ type: "TOGGLE_FINDING_SEVERITY", severity }),
                onRuleChange: (value) => dispatch({ type: "SET_FINDING_RULE", value }),
                onFileChange: (value) => dispatch({ type: "SET_FINDING_FILE", value }),
                onTextChange: (value) => dispatch({ type: "SET_FINDING_TEXT", value }),
              }}
              onActivateRow={showInCode}
              onJumpRow={showInCode}
              rowHint="指摘を選択し、下段に該当行を表示"
              tableLabel="指摘一覧"
              selectedFinding={state.findingSelected}
              sort={state.findingSort}
              onSortChange={(column) =>
                dispatch({ type: "SET_FINDING_SORT", sort: nextSortState(state.findingSort, column) })
              }
              onGoReport={() => dispatch({ type: "NAV", screen: "report" })}
              noHitMessage="現在のフィルタ条件に一致する指摘はない"
            />
          </div>
          <SplitHandle
            size={listHeight}
            min={SPLIT_PANES.findingsList.min}
            oppositeMin={SPLIT_PANES.findingsList.oppositeMin}
            orientation="horizontal"
            side="before"
            onSizeChange={(height) =>
              dispatch({ type: "SET_PANE_WIDTH", pane: "findingsList", width: height })
            }
            onCommit={() => dispatch({ type: "COMMIT_PANE_SIZE" })}
            ariaLabel="指摘一覧の高さ"
          />
        </>
      )}
      <div className="ci-findings-split__code">
        <FindingsCode
          file={sourceFile}
          line={state.sourceLine}
          document={document}
          findings={result.items}
          hint={CODE_HINT}
        >
          {paneActions}
        </FindingsCode>
      </div>
    </div>
  );
}
