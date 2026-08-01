import type { ReactElement } from "react";
import type { AssetTypeFilter } from "../../state/appState";
import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";
import { ChipRadioGroup } from "../../components/ChipRadioGroup";

/** 種別チップの並び。先頭に「すべて」を置き、以降は資産の種別を並べる。 */
const TYPE_CHIPS: readonly AssetTypeFilter[] = ["すべて", "JCL", "COBOL", "コピー句", "BMS", "その他"];

export interface ExplorerToolbarProps {
  search: string;
  onSearchChange: (value: string) => void;
  typeFilter: AssetTypeFilter;
  onTypeChange: (value: AssetTypeFilter) => void;
  onImport: () => void;
  onRun: () => void;
  runDisabled?: boolean;
}

/**
 * 資産一覧上部のツールバー。取込・名前フィルタ・種別チップ・解析実行を並べる。
 * 種別チップは単一選択なので、ラジオグループ(role=radiogroup / role=radio)として表す。
 *
 * 記号は追加(＋)・実行(▶)の意味が確立したものだけを残し、読み上げ名に混ざらないよう
 * aria-hidden で隠す。名前は可視テキストが担うので aria-label は与えない。
 */
export function ExplorerToolbar({
  search,
  onSearchChange,
  typeFilter,
  onTypeChange,
  onImport,
  onRun,
  runDisabled = false,
}: ExplorerToolbarProps): ReactElement {
  return (
    <div className="ci-explorer__toolbar">
      <Button onClick={onImport}>
        <span aria-hidden="true">＋</span> 取り込む
      </Button>
      <label className="ci-field">
        名前
        <TextInput
          placeholder="例: SYK006"
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
        />
      </label>
      <ChipRadioGroup label="種別フィルタ" options={TYPE_CHIPS} value={typeFilter} onChange={onTypeChange} />
      <div className="ci-explorer__spacer" />
      <Button variant="primary" onClick={onRun} disabled={runDisabled}>
        <span aria-hidden="true">▶</span> 解析実行
      </Button>
    </div>
  );
}
