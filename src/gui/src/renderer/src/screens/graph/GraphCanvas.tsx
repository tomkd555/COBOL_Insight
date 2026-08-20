import { useEffect, useRef, type ReactElement } from "react";
import type cytoscape from "cytoscape";
import { graphLibrary } from "../../vendor/graphLibrary";
import {
  SELECTED_NODE_CLASS,
  graphCoreOptions,
  graphLayoutOptions,
  type GraphElement,
} from "./graphModel";

export interface GraphCanvasProps {
  /** 表示する要素(ノード・エッジ)。要素が変わるたびにレイアウトを引き直す。 */
  elements: readonly GraphElement[];
  /** 選択中ノード ID。強調はクラスの付け替えで行い、レイアウトは引き直さない。 */
  selectedId: string | null;
  onSelectNode: (id: string) => void;
}

/**
 * Cytoscape の描画面。ELK 登録済みの cytoscape(vendor/graphLibrary)を用い、要素・スタイル・
 * レイアウトの決定は graphModel の純関数に委ねる。ここが持つのは canvas の生成と破棄、要素の
 * 差し替え、選択強調、ノード選択の通知だけである。
 *
 * canvas 上のノードはマウス(tap)でしか選べないため、キーボードからの選択は併置した
 * 実行順の一覧(TraceTree)が担う。
 */
export function GraphCanvas({ elements, selectedId, onSelectNode }: GraphCanvasProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const coreRef = useRef<cytoscape.Core | null>(null);
  const selectRef = useRef(onSelectNode);
  selectRef.current = onSelectNode;

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const core = graphLibrary()(graphCoreOptions(container));
    core.on("tap", "node", (event: cytoscape.EventObjectNode) => {
      selectRef.current(event.target.id());
    });
    coreRef.current = core;
    return () => {
      core.destroy();
      coreRef.current = null;
    };
  }, []);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    core.batch(() => {
      core.elements().remove();
      core.add([...elements]);
    });
    core.layout(graphLayoutOptions()).run();
  }, [elements]);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    core.nodes().removeClass(SELECTED_NODE_CLASS);
    if (selectedId !== null) {
      core.getElementById(selectedId).addClass(SELECTED_NODE_CLASS);
    }
  }, [selectedId]);

  return (
    <div
      className="ci-graph__canvas"
      ref={containerRef}
      data-testid="graph-canvas"
      role="application"
      aria-label="呼出関係図。ノードを選択すると右の詳細に情報を表示する。キーボードでは左のノード一覧から選ぶ"
    />
  );
}
