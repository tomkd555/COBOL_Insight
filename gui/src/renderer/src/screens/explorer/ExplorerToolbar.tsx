import type { ReactElement } from "react";
import type { AssetTypeFilter } from "../../state/appState";
import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";
import { ChipRadioGroup } from "../../components/ChipRadioGroup";

/** 種別チップの並び(design typeChips)。 */
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
 * 資産エクスプローラー上部のツールバー。インポート・名前フィルタ・種別チップ・解析実行を並べる。
 * 種別チップは単一選択なので、ラジオグループ(role=radiogroup / role=radio)として表す。
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
      <Button onClick={onImport}>＋ インポート</Button>
      <TextInput
        aria-label="名前でフィルタ"
        placeholder="名前でフィルタ"
        value={search}
        onChange={(event) => onSearchChange(event.target.value)}
      />
      <ChipRadioGroup label="種別フィルタ" options={TYPE_CHIPS} value={typeFilter} onChange={onTypeChange} />
      <div className="ci-explorer__spacer" />
      <Button variant="primary" onClick={onRun} disabled={runDisabled}>
        ▶ 解析実行
      </Button>
    </div>
  );
}
