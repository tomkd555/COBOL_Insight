import type { EngineResult, RuleCatalog, RulesRequest, RulesValidation } from "../../shared/ipc";
import { parseRuleCatalog } from "../../shared/ruleCatalog";

/**
 * Asking the engine about rules. It sits beside decode and save because all three are engine calls
 * the renderer makes outside a run, rather than artefact reads.
 */

/** The filesystem validating candidate rule text needs: a scratch file to hand the engine. */
export interface RulesFileSystem {
  writeText(absPath: string, text: string): Promise<void>;
  /** Removes a file; an absent file is not an error. */
  remove(absPath: string): Promise<void>;
}

export interface RulesDeps {
  fs: RulesFileSystem;
  /** Picks the scratch file candidate rule text is written to. Called once per validation. */
  tempFile(): string;
  /** Runs the engine's rules subcommand once. */
  run(request: RulesRequest): Promise<EngineResult>;
}

/** Lists the rules the engine knows about, given a rule configuration file. */
export async function listRules(deps: RulesDeps, request: RulesRequest): Promise<RuleCatalog> {
  return parseRuleCatalog((await deps.run(request)).summary);
}

/**
 * Checks candidate rule text without committing it: the text goes to a scratch file and the engine
 * reads that. Anything the engine rejects comes back as an error list, so the editor can report the
 * problem without the user having saved over a working configuration.
 */
export async function validateRules(deps: RulesDeps, raw: string): Promise<RulesValidation> {
  const candidate = deps.tempFile();
  try {
    await deps.fs.writeText(candidate, raw);
    const catalog = await listRules(deps, { rulesFile: candidate });
    return { ok: catalog.ruleErrors.length === 0, errors: catalog.ruleErrors, parsed: catalog };
  } catch (error) {
    return {
      ok: false,
      errors: [error instanceof Error ? error.message : String(error)],
      parsed: null,
    };
  } finally {
    // A failure to clean up must not overturn the verdict.
    await deps.fs.remove(candidate).catch(() => undefined);
  }
}
