import type { ReactElement, ReactNode } from "react";
import type { ScreenId } from "./screens";

export interface ScreenProps {
  /** 対応するタブの画面 ID。tabpanel と tab を aria で関連付け、名前も tab から得る。 */
  id: ScreenId;
  children: ReactNode;
}

/**
 * 画面オーバーレイの外枠。中央コンテンツ領域に position:absolute inset:0 で重なる。
 * ARIA タブパターンの tabpanel として対応タブと関連付け、名前は tab のラベルから得る。
 */
export function Screen({ id, children }: ScreenProps): ReactElement {
  return (
    <section className="ci-screen" role="tabpanel" id={`ci-screen-${id}`} aria-labelledby={`ci-tab-${id}`}>
      {children}
    </section>
  );
}
