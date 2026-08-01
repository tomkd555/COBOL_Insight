import type { InputHTMLAttributes, ReactElement } from "react";

export interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  /**
   * 入力値が受け付けられない状態か。境界線の色と aria-invalid で示す。色だけに頼らないよう、
   * 理由を述べる要素の id を aria-describedby へ渡して結びつける。
   */
  invalid?: boolean;
}

/**
 * 基本テキスト入力。名前は可視ラベル(.ci-field で包む label、または label htmlFor)から得る。
 * :focus-visible の輪郭・ホバー・無効・入力エラーの見た目は CSS 側で付与する。
 */
export function TextInput({ className, type, invalid, ...rest }: TextInputProps): ReactElement {
  const classes = ["ci-input"];
  if (invalid) classes.push("ci-input--invalid");
  if (className) classes.push(className);
  return (
    <input
      type={type ?? "text"}
      className={classes.join(" ")}
      aria-invalid={invalid ? true : undefined}
      {...rest}
    />
  );
}
