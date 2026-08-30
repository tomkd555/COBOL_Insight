/**
 * The call-graph drawing library: cytoscape with the ELK layered layout (cytoscape-elk + elkjs)
 * registered exactly once. Both are bundled locally; nothing is fetched from a CDN.
 */

import cytoscape from "cytoscape";
import elk from "cytoscape-elk";

let registered = false;

/** cytoscape with the ELK layout registered. Later calls do not register it again. */
export function graphLibrary(): typeof cytoscape {
  if (!registered) {
    cytoscape.use(elk);
    registered = true;
  }
  return cytoscape;
}
