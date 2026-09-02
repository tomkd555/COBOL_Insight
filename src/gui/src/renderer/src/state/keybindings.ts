/**
 * Keyboard chords and the commands they run. The decision is a pure function; changing state is left
 * to the caller.
 */

import type { CommandId } from "./commands";

/** What deciding a chord needs. A DOM KeyboardEvent satisfies it as it stands. */
export interface ChordEvent {
  readonly key: string;
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly altKey: boolean;
  readonly shiftKey: boolean;
  readonly target: EventTarget | null;
}

/** One binding: a chord and the command it runs. */
export interface Keybinding {
  /** How the chord is written for a person, e.g. Ctrl+Shift+P. */
  readonly chord: string;
  readonly key: string;
  readonly shift: boolean;
  readonly command: CommandId;
}

/**
 * The bindings, in the order they are tested. `key` is compared case-insensitively, so a chord works
 * with Caps Lock on. Every one of these is Ctrl-based.
 */
export const KEYBINDINGS: readonly Keybinding[] = [
  { chord: "Ctrl+B", key: "b", shift: false, command: "view.toggleSideBar" },
  { chord: "Ctrl+J", key: "j", shift: false, command: "view.togglePanel" },
  { chord: "Ctrl+W", key: "w", shift: false, command: "editor.closeTab" },
  { chord: "Ctrl+S", key: "s", shift: false, command: "editor.save" },
  { chord: "Ctrl+PageDown", key: "pagedown", shift: false, command: "editor.nextTab" },
  { chord: "Ctrl+PageUp", key: "pageup", shift: false, command: "editor.previousTab" },
  { chord: "Ctrl+Shift+E", key: "e", shift: true, command: "view.showExplorer" },
  { chord: "Ctrl+Shift+M", key: "m", shift: true, command: "view.showProblems" },
  { chord: "Ctrl+Shift+U", key: "u", shift: true, command: "view.showOutput" },
];

/** The chord that opens the command palette. It is handled apart from the command list. */
export const PALETTE_CHORD = { key: "p", shift: true, label: "Ctrl+Shift+P" } as const;

/**
 * The two-key sequences. The first key arms the sequence and the second decides the command, which
 * is how a shell offers more bindings than a single Ctrl chord has letters for. Ctrl+K S is the one
 * in use; the prefix does nothing on its own.
 */
export const SEQUENCE_PREFIX = { key: "k", label: "Ctrl+K" } as const;

/**
 * How long an armed sequence waits for its second key. VS Code waits indefinitely; here an arm made
 * outside the code editor would otherwise be completed by a plain `s` typed into it much later, so
 * the arm is dropped once the two keys can no longer be read as one gesture.
 */
export const SEQUENCE_TIMEOUT_MS = 3000;

/** The keys that only qualify another key. Pressing one is not the second key of a sequence. */
const MODIFIER_KEYS = ["Control", "Shift", "Alt", "Meta"];

/**
 * Whether the press is a modifier on its own. Releasing Ctrl and pressing it again between the two
 * keys of a sequence is ordinary typing, so it must neither complete the sequence nor cancel it.
 */
export function isModifierKey(event: ChordEvent): boolean {
  return MODIFIER_KEYS.includes(event.key);
}

const SEQUENCES: readonly { key: string; chord: string; command: CommandId }[] = [
  { key: "s", chord: "Ctrl+K S", command: "editor.saveAll" },
];

/** Whether the event arms a two-key sequence. */
export function isSequencePrefix(event: ChordEvent): boolean {
  if (!event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) {
    return false;
  }
  if (isTextEntry(event.target)) {
    return false;
  }
  return event.key.toLowerCase() === SEQUENCE_PREFIX.key;
}

/**
 * The command the second key of an armed sequence runs, or null when it completes none. The second
 * key is taken with or without Ctrl, as VS Code takes it.
 */
export function commandAfterPrefix(event: ChordEvent): CommandId | null {
  if (event.metaKey || event.altKey || isTextEntry(event.target)) {
    return null;
  }
  const key = event.key.toLowerCase();
  return SEQUENCES.find((sequence) => sequence.key === key)?.command ?? null;
}

/** Elements that take typed characters; a chord must not be stolen from them. */
const TEXT_ENTRY_TAGS = ["INPUT", "TEXTAREA", "SELECT"];

/** The marker class on the root of the code editor (Monaco). */
const CODE_EDITOR_SELECTOR = ".monaco-editor";

/**
 * Whether the user is typing into a field.
 *
 * The code editor's own multi-line input is the one exception. Monaco takes keystrokes through a
 * hidden textarea, so while code is on screen the focus is always inside one; excluding every
 * multi-line input would disable these chords exactly where they are needed most. The only way to
 * recognise that hidden textarea is the class Monaco puts on its root, so this does depend on
 * Monaco's internals.
 */
export function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement) || !TEXT_ENTRY_TAGS.includes(target.tagName)) {
    return false;
  }
  return target.closest(CODE_EDITOR_SELECTOR) === null;
}

/** Whether the event is the command palette chord. */
export function isPaletteChord(event: ChordEvent): boolean {
  if (!event.ctrlKey || event.metaKey || event.altKey || !event.shiftKey) {
    return false;
  }
  if (isTextEntry(event.target)) {
    return false;
  }
  return event.key.toLowerCase() === PALETTE_CHORD.key;
}

/** The command the chord runs, or null when it is not a chord at all. */
export function commandForChord(event: ChordEvent): CommandId | null {
  if (!event.ctrlKey || event.metaKey || event.altKey) {
    return null;
  }
  if (isTextEntry(event.target)) {
    return null;
  }
  const key = event.key.toLowerCase();
  const binding = KEYBINDINGS.find(
    (candidate) => candidate.key === key && candidate.shift === event.shiftKey,
  );
  return binding?.command ?? null;
}
