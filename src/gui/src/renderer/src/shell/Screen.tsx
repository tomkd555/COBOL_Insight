import type { ReactElement, ReactNode } from "react";
import { SCREENS, type ScreenId } from "./screens";

export interface ScreenProps {
  /** 対応するタブの画面 ID。tabpanel と tab を aria で関連付け、名前も tab から得る。 */
  id: ScreenId;
  children: ReactNode;
}

/** 画面 ID から SCREENS(タブと画面ルーティングの単一の正)のラベルを引く。 */
function screenLabel(id: ScreenId): string {
  return SCREENS.find((tab) => tab.id === id)?.label ?? id;
}

/**
 * 画面オーバーレイの外枠。中央コンテンツ領域に position:absolute inset:0 で重なる。
 * ARIA タブパターンの tabpanel として対応タブと関連付け、名前は tab のラベルから得る。
 *
 * 画面の題目を隠し見出し(h2)として置き、h1(アプリ名)の次に来る見出しレベルを常に満たす。
 * 画面内に見える題目を持つ画面は、その題目を h3 以下へ下げて隠し見出しと重複させない。
 */
export function Screen({ id, children }: ScreenProps): ReactElement {
  return (
    <section className="ci-screen" role="tabpanel" id={`ci-screen-${id}`} aria-labelledby={`ci-tab-${id}`}>
      <h2 className="ci-visually-hidden">{screenLabel(id)}</h2>
      {children}
    </section>
  );
}
