import type { ReactElement } from "react";
import type { SarifFinding } from "../../../../shared/engine-api";
import { SeverityBadge } from "../../components/SeverityBadge";
import { Button } from "../../components/Button";
import { codepageLabel } from "../explorer/assetView";
import type { SqlAdviceEntry, SqlBodyState } from "./sqlDetailModel";

export interface SqlDetailProps {
  /** 選択中の指摘。未選択は null。 */
  selected: SarifFinding | null;
  /** 選択した位置の SQL 文へ付く助言の全件。 */
  advice: readonly SqlAdviceEntry[];
  /** 原本から読んだ SQL 本文の取得状態。 */
  body: SqlBodyState;
  /** 「該当ソース行へ」でソースビューアへ遷移する。 */
  onJump: () => void;
}

/**
 * SQL助言の詳細ペイン(一覧の右)。選択した指摘の位置・SQL 本文・その位置の
 * 最適化助言を示す。本文は原本を読んで表示するだけであり、読取に失敗した場合や復号に対応しない
 * コードページの場合は、本文が空の状態と区別してその旨を示す。
 */
export function SqlDetail({ selected, advice, body, onJump }: SqlDetailProps): ReactElement {
  return (
    <aside className="ci-sql-detail" aria-label="SQL 文と最適化助言の詳細">
      {selected === null ? (
        <p className="ci-sql-detail__empty">
          一覧から助言を選ぶと
          <br />
          SQL 本文と助言の詳細を表示する
        </p>
      ) : (
        <div className="ci-sql-detail__body">
          <div className="ci-sql-detail__head">
            <h3 className="ci-sql-detail__title">{`${selected.ruleId} の対象 SQL`}</h3>
            <span className="ci-sql-detail__loc">{`${selected.file}:${selected.startLine}`}</span>
            <Button variant="primary" onClick={onJump}>
              該当ソース行へ
            </Button>
          </div>
          <section className="ci-sql-detail__section" aria-label="SQL 文">
            <div className="ci-sql-detail__section-head">SQL 文</div>
            <SqlBody body={body} />
          </section>
          <section className="ci-sql-detail__section-plain" aria-label="最適化助言">
            <div className="ci-sql-detail__advice-title">{`最適化助言 ${advice.length} 件`}</div>
            {advice.map((entry, index) => (
              // 1 行に SQL 文が2つ並ぶと同一位置・同一ルールの助言が生じるため、並び順の位置を含める。
              <div key={`${index}:${entry.finding.ruleId}`} className="ci-sql-detail__advice">
                <div className="ci-sql-detail__advice-head">
                  <SeverityBadge severity={entry.severity} />
                  <span className="ci-sql-detail__advice-id">{entry.finding.ruleId}</span>
                  <span className="ci-sql-detail__advice-name">{entry.ruleName}</span>
                </div>
                <p className="ci-sql-detail__advice-note">{entry.finding.message}</p>
              </div>
            ))}
          </section>
        </div>
      )}
    </aside>
  );
}

function SqlBody({ body }: { body: SqlBodyState }): ReactElement {
  if (body.status === "loading") {
    return <div className="ci-sql-detail__sql-empty">読み込み中…</div>;
  }
  if (body.status === "unsupported") {
    return (
      <div className="ci-sql-detail__warn" role="alert">
        コードページ {codepageLabel(body.codepage)} は本文の表示に対応していない。資産エクスプローラーで
        文字コードを指定し直すと表示できる場合がある。
      </div>
    );
  }
  if (body.status === "error") {
    return (
      <div className="ci-sql-detail__warn" role="alert">
        SQL 本文を読み取れなかった。{body.message}
      </div>
    );
  }
  if (body.status === "idle" || body.lines.length === 0) {
    return <div className="ci-sql-detail__sql-empty">表示する SQL 本文がない</div>;
  }
  return (
    <div className="ci-sql-detail__sql">
      {body.lines.map((line, index) => (
        <div key={index} className="ci-sql-detail__sql-line">
          {line}
        </div>
      ))}
      {body.truncated ? (
        <p className="ci-sql-detail__sql-note">END-EXEC が現れないため、先頭 30 行で打ち切って表示している。</p>
      ) : null}
    </div>
  );
}
