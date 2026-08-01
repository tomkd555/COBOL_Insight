import type { CobolInsightApi } from "../../shared/engine-api";

/** preload が contextBridge で公開する API を renderer 側の window 型へ結び付ける。 */
declare global {
  interface Window {
    cobolInsight: CobolInsightApi;
  }
}

export {};
