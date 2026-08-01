/**
 * roving tabindex の一覧で、キー入力から次の位置を求める。指摘一覧・呼出関係図のノード一覧・
 * 修正案の一覧が共有する。焦点の Tab 停止を選択中の1つへ集約する規約は 3 画面で同じであり、
 * 移動の規則（矢印で前後、端では反対の端へ回す、Home・End で端へ）もここに1つだけ持つ。
 *
 * 活性化（Enter・Space）の扱いは一覧ごとに違う（選択し直すか、行の操作を起こすか）ため、
 * ここでは扱わず呼び出し側に残す。
 */

/** 矢印キーによる移動量(1=次、-1=前)。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
};

/**
 * 移動先の位置を返す。移動を起こさないキー、または項目が無いときは null を返す
 * （呼び出し側は既定の動作を妨げない）。
 */
export function nextRovingIndex(key: string, current: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  if (key === "Home") {
    return 0;
  }
  if (key === "End") {
    return count - 1;
  }
  const step = STEP_KEYS[key];
  if (step === undefined) {
    return null;
  }
  return (current + step + count) % count;
}
