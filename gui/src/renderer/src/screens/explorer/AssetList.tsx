import type { ReactElement } from "react";
import { hasUndeterminedEncoding, type AssetGroup } from "./assetView";

export interface AssetListProps {
  groups: readonly AssetGroup[];
  /** 折りたたみ中のディレクトリ名の集合。 */
  collapsed: ReadonlySet<string>;
  /** 指摘件数列を出すか(解析後のみ真)。 */
  showFindingColumn: boolean;
  onToggleDir: (dir: string) => void;
  onSelect: (path: string) => void;
}

/** 種別バッジの配色修飾子(design tyMeta)。 */
const TYPE_MODIFIER: Record<string, string> = {
  JCL: "jcl",
  COBOL: "cobol",
  コピー句: "copybook",
  BMSマップ: "bms",
  その他: "other",
};

/**
 * 資産一覧。名前/種別/文字コード/解析状態/指摘件数の列を、ディレクトリ見出し(折りたたみ可)ごとに
 * 並べる。各行はクリックで選択できるボタンで、選択行はティントで示す。列見出しは常に表示する。
 */
export function AssetList({ groups, collapsed, showFindingColumn, onToggleDir, onSelect }: AssetListProps): ReactElement {
  if (groups.length === 0) {
    return (
      <div className="ci-asset-list ci-asset-list--empty">
        <p className="ci-asset-list__empty-note">該当する資産がありません。</p>
      </div>
    );
  }

  return (
    <div className="ci-asset-list">
      <div className="ci-asset-list__head">
        <div>名前</div>
        <div>種別</div>
        <div>文字コード</div>
        <div>解析</div>
        <div className="ci-asset-list__num">指摘</div>
      </div>
      {hasUndeterminedEncoding(groups) ? (
        <p className="ci-asset-list__note">
          未判定の資産は、詳細ペインで設定の既定文字コードを使って表示する。
        </p>
      ) : null}
      <div className="ci-asset-list__body">
        {groups.map((group) => {
          const isCollapsed = collapsed.has(group.dir);
          return (
            <div key={group.dir} className="ci-asset-group">
              <button
                type="button"
                className="ci-asset-group__header"
                aria-expanded={!isCollapsed}
                onClick={() => onToggleDir(group.dir)}
              >
                <span aria-hidden="true">{isCollapsed ? "▸" : "▾"}</span>
                <span className="ci-asset-group__dir">{group.dir}</span>
                <span className="ci-asset-group__count">{group.count} ファイル</span>
              </button>
              {isCollapsed
                ? null
                : group.rows.map((row) => {
                    const rowClass = row.selected ? "ci-asset-row ci-asset-row--selected" : "ci-asset-row";
                    return (
                      <button
                        key={row.item.path}
                        type="button"
                        className={rowClass}
                        aria-pressed={row.selected}
                        onClick={() => onSelect(row.item.path)}
                      >
                        <span className="ci-asset-row__name">{row.name}</span>
                        <span className="ci-asset-row__type">
                          <span className={`ci-type-badge ci-type-badge--${TYPE_MODIFIER[row.type] ?? "other"}`}>
                            {row.type}
                          </span>
                        </span>
                        <span className="ci-asset-row__enc">{row.encoding}</span>
                        <span className={`ci-asset-row__status ci-asset-row__status--${row.status.tone}`}>
                          {row.status.label}
                        </span>
                        <span className="ci-asset-row__num">
                          {showFindingColumn && row.findingCount > 0 ? row.findingCount : ""}
                        </span>
                      </button>
                    );
                  })}
            </div>
          );
        })}
      </div>
    </div>
  );
}
