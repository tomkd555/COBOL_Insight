import type { ReactElement } from "react";
import { EDGE_KIND_STYLES, NODE_KIND_STYLES } from "./graphModel";

/**
 * 凡例。ノード種別は図形名・配色・日本語名の3点で示し、色だけに頼らず種別を判別できるようにする。
 * エッジ種別は色と矢頭形状で示す。線種は解決根拠専用であり、破線が「動的・条件付き・未確定」を
 * 表すことを言葉で補う。名称と形状の説明は横に並べず縦に積む(1項目1列)。詳細ペイン幅(300px)
 * では横積みだと語の途中で折り返るため、各テキストへ行の全幅を与えて折り返りを避ける。
 */
export function GraphLegend(): ReactElement {
  return (
    <div className="ci-graph-legend">
      <h3 className="ci-graph-legend__title">凡例 ― ノード</h3>
      <ul className="ci-graph-legend__nodes">
        {NODE_KIND_STYLES.map((style) => (
          <li key={style.kind} className="ci-graph-legend__item">
            <span
              className="ci-graph-legend__swatch"
              style={{
                background: style.background,
                borderColor: style.border,
                borderStyle: style.borderStyle,
              }}
              aria-hidden="true"
            />
            <span className="ci-graph-legend__text">
              <span className="ci-graph-legend__label">{style.label}</span>
              <span className="ci-graph-legend__shape">{style.shapeLabel}</span>
            </span>
          </li>
        ))}
      </ul>
      <h3 className="ci-graph-legend__title">凡例 ― エッジ</h3>
      <ul className="ci-graph-legend__edges">
        {EDGE_KIND_STYLES.map((style) => (
          <li key={style.kind} className="ci-graph-legend__item">
            <span
              className="ci-graph-legend__line"
              style={{ borderTopColor: style.color }}
              aria-hidden="true"
            />
            <span className="ci-graph-legend__text">
              <span className="ci-graph-legend__label">{style.label}</span>
              <span className="ci-graph-legend__shape">{`矢頭 ${style.arrowLabel}`}</span>
            </span>
          </li>
        ))}
        <li className="ci-graph-legend__item">
          <span className="ci-graph-legend__line ci-graph-legend__line--dashed" aria-hidden="true" />
          <span className="ci-graph-legend__text">
            <span className="ci-graph-legend__label">破線 ― データフロー由来・未解決</span>
          </span>
        </li>
      </ul>
      <p className="ci-graph-legend__note">
        エッジ種別は色と矢頭の形で示す。線種は解決根拠だけを表し、破線は動的・条件付き・未確定の
        呼出である。未解決の動的 CALL は「可能経路」として示し、完全に解決したように見せない。
      </p>
    </div>
  );
}
