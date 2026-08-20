import { useEffect, useRef, useState, type ReactElement } from "react";
import { ChipRadioGroup } from "../components/ChipRadioGroup";
import { SEVERITY_BY_LABEL, SEVERITY_META, SEVERITY_ORDER } from "../components/severity";
import { CopybookPathList } from "../screens/settings/CopybookPathList";
import {
  ENCODING_OPTIONS,
  ENGINE_LAUNCH_INFO,
  addPath,
  isReadOnly,
  movePath,
  removePath,
  severityThresholdNote,
} from "../screens/settings/settingsModel";
import { useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings, useSettingsDispatch } from "../state/settingsStore";

/** 重大度しきい値のチップに出すラベル(高/中/低/推奨)。 */
const SEVERITY_LABELS: readonly string[] = SEVERITY_ORDER.map(
  (severity) => SEVERITY_META[severity].label,
);

/**
 * 設定のタブ。既定の文字コード・コピー句探索パス・表示する重大度のしきい値と、解析エンジンの
 * 起動の仕方を扱う。検出ルールの有効・無効はルールのタブが受け持つ。
 *
 * 値はすべて settingsStore を正とし、このタブは控えを持たない。保存はシェルがまとめて行うため、
 * タブを閉じても設定は失われない。
 */
export function SettingsTab(): ReactElement {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const settings = useSettings();
  const dispatch = useSettingsDispatch();
  const readOnly = isReadOnly(project.mode);
  const paths = settings.copybookPaths;

  const [draft, setDraft] = useState("");
  const [pathExists, setPathExists] = useState<Record<string, boolean>>({});
  const [lastAddedPath, setLastAddedPath] = useState<string | null>(null);
  const checkedPathsRef = useRef<Set<string>>(new Set());

  // 開いた時点と、新しいパスが加わった時点で実在を確かめる。確かめ済みのパスは調べ直さない。
  useEffect(() => {
    for (const path of paths.filter((value) => !checkedPathsRef.current.has(value))) {
      checkedPathsRef.current.add(path);
      window.cobolInsight
        .checkDirectoryExists(path)
        .then((exists) => setPathExists((previous) => ({ ...previous, [path]: exists })))
        .catch(() => {
          checkedPathsRef.current.delete(path);
        });
    }
  }, [paths]);

  const addWarning =
    lastAddedPath !== null && pathExists[lastAddedPath] === false
      ? `${lastAddedPath} が見つかりません。`
      : null;

  function applyPaths(next: string[]): void {
    dispatch({ type: "SET_COPYBOOK_PATHS", paths: next });
  }

  function onAddPath(): void {
    const result = addPath(paths, draft);
    if (!result.added) {
      projectDispatch({ type: "LOG", text: result.reason ?? "" });
      return;
    }
    applyPaths(result.paths);
    setDraft("");
    setLastAddedPath(result.paths[result.paths.length - 1] ?? null);
  }

  function onRemovePath(index: number): void {
    const removed = paths[index];
    applyPaths(removePath(paths, index));
    if (removed === lastAddedPath) {
      setLastAddedPath(null);
    }
    projectDispatch({
      type: "LOG",
      text: `コピー句探索パスから ${removed} を外しました。`,
    });
  }

  return (
    <div className="ci-settings">
      <div className="ci-settings__page">
        <h3 className="ci-settings__title">設定</h3>
        {readOnly ? (
          <div className="ci-settings__lock" role="status">
            解析の実行中は設定を変更できません（読み取り専用）。
          </div>
        ) : null}

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">既定の文字コード</h4>
          <p className="ci-settings__desc">
            解析エンジンが文字コードを判定できなかった資産で、文字コードの選択欄の初期値として
            用います。資産ごとの手動指定が常に優先されます。
          </p>
          <select
            className="ci-settings__select"
            aria-label="既定の文字コード"
            value={settings.defaultEncoding}
            disabled={readOnly}
            onChange={(event) =>
              dispatch({ type: "SET_DEFAULT_ENCODING", value: event.target.value })
            }
          >
            {ENCODING_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </section>

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">コピー句探索パス</h4>
          <p className="ci-settings__desc">
            並べた順に探索します。同名のコピー句が複数あるときは、先に見つかったものを使います。
          </p>
          <CopybookPathList
            paths={paths}
            draft={draft}
            onDraftChange={(value) => {
              setLastAddedPath(null);
              setDraft(value);
            }}
            onMove={(index, delta) => applyPaths(movePath(paths, index, delta))}
            onRemove={onRemovePath}
            onAdd={onAddPath}
            disabled={readOnly}
            existence={pathExists}
            addWarning={addWarning}
          />
        </section>

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">表示する重大度のしきい値</h4>
          <p className="ci-settings__desc">
            選んだ重大度以上の指摘を下部パネルへ表示します。しきい値より低い重大度は一覧から外れ、
            その重大度の絞り込みも操作できなくなります。
          </p>
          <ChipRadioGroup
            label="表示する重大度のしきい値"
            options={SEVERITY_LABELS}
            value={SEVERITY_META[settings.severityThreshold].label}
            onChange={(label) =>
              dispatch({ type: "SET_THRESHOLD", severity: SEVERITY_BY_LABEL[label] })
            }
          />
          <p className="ci-settings__note">{severityThresholdNote(settings.severityThreshold)}</p>
        </section>

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">解析エンジンの実行</h4>
          <p className="ci-settings__desc">
            解析は同梱の解析エンジンが別のプログラムとして担い、結果をファイルで受け取ります。
            ネットワーク接続は行いません。
          </p>
          <dl className="ci-settings__versions">
            {ENGINE_LAUNCH_INFO.map((entry) => (
              <div key={entry.label} className="ci-settings__version">
                <dt className="ci-settings__version-label">{entry.label}</dt>
                <dd className="ci-settings__version-value">{entry.value}</dd>
              </div>
            ))}
            <div className="ci-settings__version">
              <dt className="ci-settings__version-label">COBOL Insight</dt>
              <dd className="ci-settings__version-value">{project.version}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  );
}
