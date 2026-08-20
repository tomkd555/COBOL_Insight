import { useRef, type KeyboardEvent, type ReactElement } from "react";
import { FilterChip } from "./FilterChip";

export interface ChipRadioGroupProps<T extends string> {
  /** グループの名前(aria-label)。 */
  label: string;
  /** 選択肢。表示順にそのままチップとして並べる。 */
  options: readonly T[];
  /** 選択中の値。 */
  value: T;
  onChange: (value: T) => void;
  /** 値から表示名を引く。省略すると値をそのまま出す(値が表示名を兼ねる場合)。 */
  labelOf?: (value: T) => string;
}

/** 矢印キーによる移動量(1=次、-1=前)。Home/End は端へ移す。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowRight: 1,
  ArrowDown: 1,
  ArrowLeft: -1,
  ArrowUp: -1,
};

/**
 * 単一選択のチップ群(ラジオグループ)。ARIA のラジオグループの操作規約に従い、グループ全体を
 * 1 つのタブストップにし(選択中のチップだけ tabindex=0)、矢印キーで選択を移す。Home・End で
 * 端へ移す。矢印キーによる移動は選択の変更を伴う(selection follows focus)。
 */
export function ChipRadioGroup<T extends string>({
  label,
  options,
  value,
  onChange,
  labelOf,
}: ChipRadioGroupProps<T>): ReactElement {
  const groupRef = useRef<HTMLDivElement>(null);

  /** グループ内の index 番目のチップへ焦点を移す。 */
  function focusChip(index: number): void {
    const chips = groupRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]');
    chips?.[index]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const current = options.indexOf(value);
    const last = options.length - 1;
    let next: number;
    if (event.key === "Home") {
      next = 0;
    } else if (event.key === "End") {
      next = last;
    } else {
      const step = STEP_KEYS[event.key];
      if (step === undefined) return;
      // 端では反対の端へ回す。
      next = (current + step + options.length) % options.length;
    }
    event.preventDefault();
    onChange(options[next]);
    focusChip(next);
  }

  return (
    <div
      ref={groupRef}
      className="ci-chip-group"
      role="radiogroup"
      aria-label={label}
      onKeyDown={onKeyDown}
    >
      {options.map((option) => (
        <FilterChip
          key={option}
          label={labelOf === undefined ? option : labelOf(option)}
          active={option === value}
          single
          tabIndex={option === value ? 0 : -1}
          onClick={() => onChange(option)}
        />
      ))}
    </div>
  );
}
