import type { ReactElement } from "react";
import { EditorTabs } from "./EditorTabs";
import { EmptyState } from "../components/EmptyState";
import { FixTab } from "../tabs/FixTab";
import { GraphTab } from "../tabs/GraphTab";
import { ReportTab } from "../tabs/ReportTab";
import { RulesTab } from "../tabs/RulesTab";
import { SettingsTab } from "../tabs/SettingsTab";
import { SourceTab } from "../tabs/SourceTab";
import { useWorkbench, useWorkbenchDispatch, type WorkbenchTab } from "../state/workbenchStore";

export interface EditorAreaProps {
  /** カーソル位置が変わったときに呼ぶ。ステータスバーが受け取る。 */
  onCursor: (line: number, column: number) => void;
}

function tabContent(tab: WorkbenchTab, onCursor: EditorAreaProps["onCursor"]): ReactElement {
  switch (tab.kind) {
    case "source":
      // path を持たない資産のタブは作られない(sourceTab が必ず持たせる)。
      return tab.path === null ? (
        <EmptyState
          title="資産を特定できません"
          description="タブを閉じて、開き直してください。"
        />
      ) : (
        <SourceTab path={tab.path} line={tab.line} onCursor={onCursor} />
      );
    case "fix":
      return <FixTab />;
    case "graph":
      return <GraphTab />;
    case "rules":
      return <RulesTab />;
    case "report":
      return <ReportTab />;
    case "settings":
      return <SettingsTab />;
  }
}

/**
 * 本文領域。タブ帯と、選択中のタブの中身を出す。選択していないタブは描かない
 * (Monaco の面を隠して残すと、開いたタブの数だけ実 DOM と worker が残る)。
 */
export function EditorArea({ onCursor }: EditorAreaProps): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();
  const active = workbench.tabs.find((tab) => tab.id === workbench.activeTabId) ?? null;

  return (
    <section className="ci-editor" aria-label="本文">
      <EditorTabs />
      {active === null ? (
        <div className="ci-editor__body">
          <EmptyState
            icon="▤"
            title="資産を開いていません"
            description="左のエクスプローラーから資産を選ぶと、この場所に本文が出ます。"
            actionLabel="エクスプローラーを開く"
            onAction={() => dispatch({ type: "SHOW_SIDE", view: "explorer" })}
          />
        </div>
      ) : (
        <div
          className="ci-editor__body"
          role="tabpanel"
          id={`ci-tabpanel-${active.id}`}
          aria-labelledby={`ci-tab-${active.id}`}
          data-testid={`tabpanel-${active.id}`}
        >
          {tabContent(active, onCursor)}
        </div>
      )}
    </section>
  );
}
