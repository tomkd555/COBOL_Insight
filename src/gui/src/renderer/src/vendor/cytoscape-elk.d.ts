/**
 * cytoscape-elk ships no type definitions, so it is declared here as a cytoscape extension. It is a
 * UMD module wrapping elkjs/lib/elk.bundled.js; both are bundled locally and reach no network.
 */
declare module "cytoscape-elk" {
  import type cytoscape from "cytoscape";
  const extension: cytoscape.Ext;
  export default extension;
}
