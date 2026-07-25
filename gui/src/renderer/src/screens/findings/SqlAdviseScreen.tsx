import { useEffect, useMemo, useState, type ReactElement } from "react";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SCREEN_META } from "../screenMeta";
import { previewCodepage } from "../explorer/assetView";
import { FindingsView } from "./FindingsView";
import { SqlDetail } from "./SqlDetail";
import type { FindingFilters } from "./findingsModel";
import { adviceAt, extractSqlStatement, type SqlBodyState } from "./sqlDetailModel";

/** SQL助言の running 表示の段(構文木の走査。3 段の構文→制御フロー→データフローではない)。 */
const SQL_RUN_STAGES = ["SQL 構文木の走査（S001〜S006）"];

/** 画面上部の説明(design:454)。判定が構文レベルに限られることを明示する。 */
const SQL_INTRO =
  "埋め込み Db2 SQL（EXEC SQL … END-EXEC）を抽出し、構文レベルの最適化助言（S001〜S006）を提示します。実行計画やカタログには依存しません。";

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * SQL助言(sql-advise)。表示する助言は「▶ 解析実行」が起動した sql-advise の SARIF を AppState へ
 * 収めたもので、この画面は sql-advise を起動しない。一覧 UI は指摘一覧と共有し(FindingsView)、
 * フィルタ・ソート・選択はいずれも AppState に持つためタブを移動しても失われない。
 *
 * 行を選ぶと右の詳細ペインへ、原本から読んだ SQL 本文とその位置の助言を出す(design scSql)。
 * ソースビューアへの遷移は詳細ペインの「該当ソース行へ →」で行う。SQL 文の総数は SARIF から
 * 厳密に導けないため提示しない(裁定 A8)。起動または SARIF 読取の失敗は 0 件と区別する。
 */
export function SqlAdviseScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const [body, setBody] = useState<SqlBodyState>({ status: "idle" });

  const meta = SCREEN_META.sql;
  const result = state.sqlAdvice;
  const selected = state.sqlSelected;
  const inputDir = state.project.inputDir;

  // 本文の復号に用いるコードページ。資産一覧の検出値(手動指定があればそれ)を引く。
  const codepage = useMemo(() => {
    if (selected === null || state.inventory.status !== "ready") return null;
    const item = state.inventory.items.find((entry) => entry.path === selected.file);
    return item === undefined ? null : previewCodepage(item, state.encodingSel, state.defaultEncoding);
  }, [selected, state.inventory, state.encodingSel, state.defaultEncoding]);

  // 選択が変わるたびに原本を読み直す。応答が返る前に選択が変わった場合は古い応答を捨てる。
  useEffect(() => {
    if (selected === null || inputDir === null) {
      setBody({ status: "idle" });
      return;
    }
    let current = true;
    setBody({ status: "loading" });
    window.cobolInsight
      .readSourceText({ inputDir, path: selected.file, codepage })
      .then((source) => {
        if (!current) return;
        if (source.unsupported) {
          setBody({ status: "unsupported", codepage: source.codepage });
          return;
        }
        const statement = extractSqlStatement(source.text, selected.startLine);
        setBody({ status: "ready", lines: statement.lines, truncated: statement.truncated });
      })
      .catch((error: unknown) => {
        if (current) setBody({ status: "error", message: messageOf(error) });
      });
    return () => {
      current = false;
    };
  }, [inputDir, selected, codepage]);

  const advice = useMemo(() => {
    if (selected === null || result.status !== "ready") return [];
    return adviceAt(result.items, selected.file, selected.startLine);
  }, [selected, result]);

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="表示できる SQL がありません"
        description="解析がまだ実行されていないか、解析対象に埋め込み SQL が見つかりませんでした。"
      />
    );
  }
  if (state.mode === "running") {
    return <RunningIndicator title={meta.runningTitle} stages={SQL_RUN_STAGES} />;
  }
  if (result.status === "error") {
    return (
      <EmptyState
        icon="！"
        title="SQL 助言を取得できませんでした"
        description={`sql-advise の実行または SARIF の読取に失敗したため、助言の件数は不明です。${result.message}`}
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.status === "none") {
    return (
      <EmptyState
        title="SQL 助言をまだ取得していません"
        description="解析実行が完了していないため、SQL 助言を表示できません。資産エクスプローラーで解析を実行してください。"
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.items.length === 0) {
    return (
      <EmptyState
        title="表示できる SQL がありません"
        description="解析対象に、埋め込み SQL の最適化助言（S001〜S006）は見つかりませんでした。"
      />
    );
  }

  const filters: FindingFilters = {
    severity: state.sqlSeverity,
    threshold: state.severityThreshold,
    rule: state.sqlRule,
    file: state.sqlFile,
    text: state.sqlText,
    sort: state.sqlSort,
  };

  return (
    <div className="ci-sql">
      <p className="ci-sql__intro">{SQL_INTRO}</p>
      <div className="ci-sql__body">
        <FindingsView
          findings={result.items}
          filters={filters}
          handlers={{
            onToggleSeverity: (severity) => dispatch({ type: "TOGGLE_SQL_SEVERITY", severity }),
            onRuleChange: (value) => dispatch({ type: "SET_SQL_RULE", value }),
            onFileChange: (value) => dispatch({ type: "SET_SQL_FILE", value }),
            onTextChange: (value) => dispatch({ type: "SET_SQL_TEXT", value }),
            onSortChange: (sort) => dispatch({ type: "SET_SQL_SORT", sort }),
          }}
          onActivateRow={(row) => dispatch({ type: "SELECT_SQL_ADVICE", finding: row.finding })}
          rowHint="SQL 本文と助言の詳細を表示"
          tableLabel="SQL 助言一覧"
          selectedFinding={selected}
          onGoReport={() => dispatch({ type: "NAV", screen: "report" })}
          noHitMessage="現在のフィルタ条件に一致する SQL 助言はありません"
        />
        <SqlDetail
          selected={selected}
          advice={advice}
          body={body}
          onJump={() => {
            if (selected === null) return;
            dispatch({
              type: "JUMP",
              file: selected.file,
              line: selected.startLine,
              from: "SQL助言",
            });
          }}
        />
      </div>
    </div>
  );
}
