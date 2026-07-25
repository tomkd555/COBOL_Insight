import type { InputHTMLAttributes, ReactElement } from "react";

export interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  /** アクセシブルな名前。可視ラベルを持たない絞り込み入力等で必須。 */
  "aria-label": string;
}

/**
 * 基本テキスト入力。可視ラベルを持たない用途が多いため aria-label を必須とする。
 * :focus-visible の輪郭は CSS 側で付与する。
 */
export function TextInput({ className, type, ...rest }: TextInputProps): ReactElement {
  const classes = ["ci-input"];
  if (className) classes.push(className);
  return <input type={type ?? "text"} className={classes.join(" ")} {...rest} />;
}
