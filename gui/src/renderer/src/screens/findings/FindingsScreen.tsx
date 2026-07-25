import type { ReactElement } from "react";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SCREEN_META } from "../screenMeta";
import { FindingsView } from "./FindingsView";
import type { FindingFilters } from "./findingsModel";

/**
 * 指摘一覧(lint)。表示する指摘は「▶ 解析実行」が起動した lint の SARIF を AppState へ収めたもので、
 * この画面は lint を起動しない(タブを開くたびの再解析が起きない)。フィルタ(重大度/ルール/
 * ファイル/内容)・ソート・行ジャンプは共有一覧 UI(FindingsView)へ委ね、フィルタ状態は AppState に持つ。
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
        description="資産をインポートして解析を実行すると、31 種のバグ検出ルール（R001〜R031）による指摘を一覧できます。"
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
        description={`lint の実行または SARIF の読取に失敗したため、指摘の件数は不明です。${result.message}`}
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.status === "none") {
    return (
      <EmptyState
        title="指摘をまだ取得していません"
        description="解析実行が完了していないため、指摘を表示できません。資産エクスプローラーで解析を実行してください。"
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
        description="解析は正常に完了し、有効なルールに該当する箇所は検出されませんでした。"
      />
    );
  }

  const filters: FindingFilters = {
    severity: state.findingSeverity,
    threshold: state.severityThreshold,
    rule: state.findingRule,
    file: state.findingFile,
    text: state.findingText,
    sort: state.findingSort,
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
        onSortChange: (sort) => dispatch({ type: "SET_FINDING_SORT", sort }),
      }}
      onActivateRow={(row) =>
        dispatch({ type: "JUMP", file: row.finding.file, line: row.finding.startLine, from: "指摘一覧" })
      }
      rowHint="ソースの該当行へジャンプ"
      tableLabel="指摘一覧"
      onGoReport={() => dispatch({ type: "NAV", screen: "report" })}
      noHitMessage="現在のフィルタ条件に一致する指摘はありません"
    />
  );
}
