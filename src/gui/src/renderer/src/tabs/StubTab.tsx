import type { ReactElement } from "react";
import { EmptyState } from "../components/EmptyState";
import type { TabKind } from "../state/workbenchStore";

/** 中身をまだ移していないタブの種類。 */
export type StubKind = Exclude<TabKind, "source" | "fix">;

/** 中身をまだ移していないタブの案内。 */
const PENDING: Readonly<Record<StubKind, string>> = {
  graph: "ジョブから段落までの呼出関係を、実行順の一覧と図で示す面です。",
  rules: "組み込みと利用者定義のルールを一覧し、有効・無効を切り替える面です。",
  report: "解析の結果を HTML またはテキストで書き出す面です。",
  settings: "コピー句探索パス・既定の文字コード・重大度しきい値を決める面です。",
};

export interface StubTabProps {
  kind: StubKind;
  title: string;
}

/**
 * 中身を後の回で入れるタブ。何が入るのかを述べるだけで、操作は持たない。
 * 空の面を黙って出すと、機能が壊れているのか未着手なのかを利用者が区別できない。
 */
export function StubTab({ kind, title }: StubTabProps): ReactElement {
  return (
    <EmptyState
      icon="…"
      title={`${title}はまだ用意できていません`}
      description={PENDING[kind]}
    />
  );
}
