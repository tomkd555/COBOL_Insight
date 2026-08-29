/// <reference types="vite/client" />

/**
 * Vite's `?worker&inline` import form. The plugin turns the module into a Worker constructor with
 * its code embedded, which is what lets Monaco start a worker from a file:// page.
 */
declare module "*?worker&inline" {
  const WorkerConstructor: new () => Worker;
  export default WorkerConstructor;
}

/** The codicon registration is a side-effect-only CSS import with no exports of its own. */
declare module "monaco-editor/features/codicon/register";
