import type { ButtonHTMLAttributes, ReactElement } from "react";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** primary=主アクション(青地白文字)、default=通常(白地枠線)。既定は default。 */
  variant?: "primary" | "default";
}

/**
 * 基本ボタン。type は既定で button(誤送信防止)。variant で主/通常の見た目を切り替える。
 * :focus-visible の輪郭は CSS 側で付与する。
 */
export function Button({ variant = "default", className, type, children, ...rest }: ButtonProps): ReactElement {
  const classes = ["ci-btn", `ci-btn--${variant}`];
  if (className) classes.push(className);
  return (
    <button type={type ?? "button"} className={classes.join(" ")} {...rest}>
      {children}
    </button>
  );
}
