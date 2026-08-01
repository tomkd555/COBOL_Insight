import type { ReactElement } from "react";
import type { AssetInventoryItem } from "../../../../shared/engine-api";
import type { ScreenMode } from "../../state/appState";
import { analysisStatus, displayType } from "./assetView";
import { EncodingSelect } from "./EncodingSelect";
import { DecodePreview } from "./DecodePreview";
import type { SourcePreview } from "./sourcePreview";

export interface AssetDetailProps {
  /** 選択中の資産。未選択は null。 */
  item: AssetInventoryItem | null;
  mode: ScreenMode;
  /** この資産に対する lint 指摘の件数(供給源は lint の SARIF)。 */
  findingCount: number;
  /** 文字コード選択欄の現在値(ENCODING_OPTIONS のいずれか)。 */
  encodingValue: string;
  onEncodingChange: (value: string) => void;
  /** デコードプレビューの状態(供給源は main の readSourceText)。 */
  preview: SourcePreview;
}

/**
 * 右 330px の詳細ペイン。選択資産の名称・パス・種別・解析状態・指摘件数を示し、文字コード選択と
 * デコードプレビューを添える。未選択のときは選択を促す。
 *
 * ペインの題目は画面内の区画なので見出しレベル3とする。
 */
export function AssetDetail({
  item,
  mode,
  findingCount,
  encodingValue,
  onEncodingChange,
  preview,
}: AssetDetailProps): ReactElement {
  return (
    <aside className="ci-detail" aria-label="資産の詳細と文字コード">
      <h3 className="ci-detail__title">資産の詳細と文字コード</h3>
      {item === null ? (
        <p className="ci-detail__empty">
          資産を選択すると詳細と
          <br />
          デコードプレビューを表示する
        </p>
      ) : (
        <DetailBody
          item={item}
          mode={mode}
          findingCount={findingCount}
          encodingValue={encodingValue}
          onEncodingChange={onEncodingChange}
          preview={preview}
        />
      )}
    </aside>
  );
}

function DetailBody({
  item,
  mode,
  findingCount,
  encodingValue,
  onEncodingChange,
  preview,
}: {
  item: AssetInventoryItem;
  mode: ScreenMode;
  findingCount: number;
  encodingValue: string;
  onEncodingChange: (value: string) => void;
  preview: SourcePreview;
}): ReactElement {
  const status = analysisStatus(item, mode);
  const analyzed = mode === "results" || mode === "error";
  return (
    <div className="ci-detail__body">
      <div>
        <div className="ci-detail__name">{item.name}</div>
        <div className="ci-detail__path">{item.path}</div>
      </div>
      <dl className="ci-detail__meta">
        <div>
          <dt>種別</dt>
          <dd>{displayType(item.type)}</dd>
        </div>
        <div>
          <dt>解析</dt>
          <dd className={`ci-detail__status--${status.tone}`}>{status.label}</dd>
        </div>
        <div>
          <dt>指摘</dt>
          <dd>{analyzed ? `${findingCount} 件` : "—"}</dd>
        </div>
      </dl>
      <EncodingSelect value={encodingValue} onChange={onEncodingChange} />
      <DecodePreview preview={preview} />
    </div>
  );
}
