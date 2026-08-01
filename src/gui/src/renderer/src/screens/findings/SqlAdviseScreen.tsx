import { useMemo, type CSSProperties, type ReactElement } from "react";
import { SPLIT_PANES } from "../../state/appState";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { CodeFocusButton } from "../../components/CodeFocusButton";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SplitHandle } from "../../components/SplitHandle";
import { SCREEN_META } from "../screenMeta";
import { previewCodepage } from "../explorer/assetView";
import { useSourceDocument } from "../viewer/useSourceDocument";
import { FindingsCode } from "./FindingsCode";
import { FindingsView } from "./FindingsView";
import { SqlDetail } from "./SqlDetail";
import { nextSortState, type FindingFilters } from "./findingsModel";
import { adviceAt, extractSqlStatement, type SqlBodyState } from "./sqlDetailModel";

/**
 * 実行中に提示する段。この画面が扱うのは sql-lint だけなので、解析実行の3段
 * (scan → lint → sql-lint)ではなく、SQL 構文木の走査だけを示す。
 */
const SQL_RUN_STAGES = ["SQL 構文木の走査（S001〜S006）"];

/** 画面上部の説明。指摘が構文レベルの判定に限られることを、一覧を見る前に明示する。 */
const SQL_INTRO =
  "埋め込み Db2 SQL（EXEC SQL … END-EXEC）を抽出し、構文レベルの最適化の指摘（S001〜S006）を提示する。実行計画やカタログには依存しない。";

/** 指摘を選ぶ前の下段に出す案内。 */
const CODE_HINT = "一覧から指摘を選ぶと、この下に該当資産の原本を出して該当行へ移る。";

/**
 * SQL指摘(sql-lint)。表示する指摘は「▶ 解析実行」が起動した sql-lint の SARIF を AppState へ
 * 収めたもので、この画面は sql-lint を起動しない。一覧 UI は指摘一覧と共有し(FindingsView)、
 * フィルタ・ソート・選択はいずれも AppState に持つためタブを移動しても失われない。
 *
 * 上に一覧、下に「COBOL 原本」と「SQL 本文と指摘の詳細」を並べる。行を選ぶと下段の原本が該当行へ
 * 移り、右の詳細ペインへ切り出した SQL 本文とその位置の指摘を出す。原本と詳細は同じ本文から作る
 * (読み取りは1回で済む)。ソースビューアへの遷移は詳細ペインの「該当ソース行へ」で行う。SQL 文の
 * 総数は SARIF から厳密に導けないため提示しない。起動または SARIF 読取の失敗は 0 件と区別する。
 */
export function SqlAdviseScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();

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

  const document = useSourceDocument(
    inputDir !== null,
    inputDir,
    selected === null ? "" : selected.file,
    codepage,
  );

  // 詳細ペインが出す SQL 本文。原本と同じ本文から切り出し、読み取りを二重に行わない。
  const body: SqlBodyState = useMemo(() => {
    if (selected === null || document.status === "idle") {
      return { status: "idle" };
    }
    if (document.status === "loading") {
      return { status: "loading" };
    }
    if (document.status === "unsupported") {
      return { status: "unsupported", codepage: document.codepage };
    }
    if (document.status === "error") {
      return { status: "error", message: document.message };
    }
    const statement = extractSqlStatement(document.text, selected.startLine);
    return { status: "ready", lines: statement.lines, unterminated: statement.unterminated };
  }, [selected, document]);

  const advice = useMemo(() => {
    if (selected === null || result.status !== "ready") return [];
    return adviceAt(result.items, selected.file, selected.startLine);
  }, [selected, result]);

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="表示できる SQL がありません"
        description="解析がまだ実行されていないか、解析対象に埋め込み SQL が見つからなかった。"
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
        title="SQL指摘を取得できませんでした"
        description={`SQL指摘の実行または検出結果の読み取りに失敗したため、指摘の件数は分からない。${result.message}`}
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.status === "none") {
    return (
      <EmptyState
        title="SQL指摘をまだ取得していません"
        description="解析実行が完了していないため、SQL指摘を表示できない。資産一覧で解析を実行する。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (result.items.length === 0) {
    return (
      <EmptyState
        title="表示できる SQL がありません"
        description="解析対象に、埋め込み SQL の最適化の指摘（S001〜S006）は見つからなかった。"
      />
    );
  }

  const filters: FindingFilters = {
    severity: state.sqlSeverity,
    threshold: state.severityThreshold,
    rule: state.sqlRule,
    file: state.sqlFile,
    text: state.sqlText,
  };

  const listHeight = state.paneWidths.sqlList;
  const detailWidth = state.paneWidths.sqlDetail;
  // 一覧の高さは画面全体の容れ物に、詳細ペインの幅と原本へ残す最小は下段の容れ物に効かせる
  // (下段の --ci-opposite-min が画面全体の値を上書きし、各ペインが自分の相手側の最小を見る)。
  const screenStyle = {
    "--ci-sql-list-h": `${listHeight}px`,
    "--ci-opposite-min": `${SPLIT_PANES.sqlList.oppositeMin}px`,
  } as CSSProperties;
  const bodyStyle = {
    "--ci-sql-detail-w": `${detailWidth}px`,
    "--ci-opposite-min": `${SPLIT_PANES.sqlDetail.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-sql" style={screenStyle}>
      <p className="ci-sql__intro">{SQL_INTRO}</p>
      {state.codeFocus ? null : (
        <>
          <div className="ci-sql__list">
            <FindingsView
              findings={result.items}
              filters={filters}
              handlers={{
                onToggleSeverity: (severity) => dispatch({ type: "TOGGLE_SQL_SEVERITY", severity }),
                onRuleChange: (value) => dispatch({ type: "SET_SQL_RULE", value }),
                onFileChange: (value) => dispatch({ type: "SET_SQL_FILE", value }),
                onTextChange: (value) => dispatch({ type: "SET_SQL_TEXT", value }),
              }}
              onActivateRow={(row) => dispatch({ type: "SELECT_SQL_ADVICE", finding: row.finding })}
              rowHint="SQL 本文と指摘の詳細を表示"
              tableLabel="SQL指摘一覧"
              selectedFinding={selected}
              sort={state.sqlSort}
              onSortChange={(column) =>
                dispatch({ type: "SET_SQL_SORT", sort: nextSortState(state.sqlSort, column) })
              }
              onGoReport={() => dispatch({ type: "NAV", screen: "report" })}
              noHitMessage="現在のフィルタ条件に一致する SQL指摘はない"
            />
          </div>
          <SplitHandle
            size={listHeight}
            min={SPLIT_PANES.sqlList.min}
            oppositeMin={SPLIT_PANES.sqlList.oppositeMin}
            orientation="horizontal"
            side="before"
            onSizeChange={(height) =>
              dispatch({ type: "SET_PANE_WIDTH", pane: "sqlList", width: height })
            }
            onCommit={() => dispatch({ type: "COMMIT_PANE_SIZE" })}
            ariaLabel="SQL指摘一覧の高さ"
          />
        </>
      )}
      <div className="ci-sql__body" style={bodyStyle}>
        <div className="ci-sql__code">
          <FindingsCode
            file={selected === null ? "" : selected.file}
            line={selected === null ? null : selected.startLine}
            document={document}
            findings={result.items}
            hint={CODE_HINT}
          >
            {/* 最大化の操作は畳む一覧の中ではなく、常に見えているコード面の見出しへ置く。 */}
            <CodeFocusButton
              active={state.codeFocus}
              onToggle={() => dispatch({ type: "TOGGLE_CODE_FOCUS" })}
              target="一覧"
            />
          </FindingsCode>
        </div>
        <SplitHandle
          size={detailWidth}
          min={SPLIT_PANES.sqlDetail.min}
          oppositeMin={SPLIT_PANES.sqlDetail.oppositeMin}
          onSizeChange={(width) => dispatch({ type: "SET_PANE_WIDTH", pane: "sqlDetail", width })}
          onCommit={() => dispatch({ type: "COMMIT_PANE_SIZE" })}
          ariaLabel="SQL 文と指摘の詳細ペインの幅"
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
              from: "SQL指摘",
            });
          }}
        />
      </div>
    </div>
  );
}
