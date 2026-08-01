import type { ReactElement } from "react";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SCREEN_META } from "../screenMeta";
import { FindingsView } from "./FindingsView";
import { nextSortState, type FindingFilters } from "./findingsModel";

/**
 * 指摘一覧(lint)。表示する指摘は「▶ 解析実行」が起動した lint の SARIF を AppState へ収めたもので、
 * この画面は lint を起動しない(タブを開くたびの再解析が起きない)。フィルタ(重大度/ルール/
 * ファイル/内容)・ソート・選択は共有一覧 UI(FindingsView)へ委ね、いずれも AppState に持つ。
 *
 * 行のクリック・Enter/Space は選択だけを行う(SQL助言・呼出関係図と同じ規則)。ソースへの
 * ジャンプは、詳細ペインを持たないこの画面ではファイル・行のセル自身を明示的なボタンにして行う。
 *
 * 空状態は 2 バリアント: mode=empty は「未解析」、解析済みで 0 件は「指摘なし」。lint の起動または
 * SARIF 読取が失敗した場合は 0 件と区別し、失敗として理由を示す。
 */
export function FindingsScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.findings;
  const result = state.findings;

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="解析がまだ実行されていません"
        description="資産をインポートして解析を実行すると、31 種の検出ルール（R001〜R031）による指摘を一覧できる。"
        actionLabel="資産エクスプローラーへ"
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
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.status === "none") {
    return (
      <EmptyState
        title="指摘をまだ取得していません"
        description="解析実行が完了していないため、指摘を表示できない。資産エクスプローラーで解析を実行する。"
        actionLabel="資産エクスプローラーへ"
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

  return (
    <FindingsView
      findings={result.items}
      filters={filters}
      handlers={{
        onToggleSeverity: (severity) => dispatch({ type: "TOGGLE_FINDING_SEVERITY", severity }),
        onRuleChange: (value) => dispatch({ type: "SET_FINDING_RULE", value }),
        onFileChange: (value) => dispatch({ type: "SET_FINDING_FILE", value }),
        onTextChange: (value) => dispatch({ type: "SET_FINDING_TEXT", value }),
      }}
      onActivateRow={(row) => dispatch({ type: "SELECT_FINDING", finding: row.finding })}
      onJumpRow={(row) =>
        dispatch({ type: "JUMP", file: row.finding.file, line: row.finding.startLine, from: "指摘一覧" })
      }
      rowHint="指摘を選択"
      tableLabel="指摘一覧"
      selectedFinding={state.findingSelected}
      sort={state.findingSort}
      onSortChange={(column) => dispatch({ type: "SET_FINDING_SORT", sort: nextSortState(state.findingSort, column) })}
      onGoReport={() => dispatch({ type: "NAV", screen: "report" })}
      noHitMessage="現在のフィルタ条件に一致する指摘はない"
    />
  );
}
