import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { text } from "../i18n/text";
import { availableCommands, filterCommands, type Command } from "../state/commands";

export interface CommandPaletteProps {
  commands: readonly Command[];
  onClose: () => void;
}

/**
 * The command palette. Typing filters the list, the arrow keys move the highlight, Enter runs the
 * highlighted command and Escape closes without running anything.
 *
 * Commands whose `when` is false are left out entirely rather than shown disabled: a palette exists
 * to be typed into and run, so an entry that cannot run is only in the way.
 */
export function CommandPalette({ commands, onClose }: CommandPaletteProps): ReactElement {
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const matches = useMemo(
    () => filterCommands(availableCommands(commands), query),
    [commands, query],
  );

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Keep the highlight on a row that exists as the filter narrows the list.
  useEffect(() => {
    setHighlight((current) => Math.min(current, Math.max(matches.length - 1, 0)));
  }, [matches.length]);

  const runHighlighted = (): void => {
    const command = matches[highlight];
    // Close first: a command that changes the layout should not be run behind a still-open palette.
    onClose();
    command?.run();
  };

  return (
    <div className="ci-palette" data-testid="command-palette">
      <div className="ci-palette__backdrop" onClick={onClose} aria-hidden="true" />
      <div className="ci-palette__dialog" role="dialog" aria-modal="true" aria-label={text.palette.label}>
        <input
          ref={inputRef}
          type="text"
          className="ci-palette__input"
          placeholder={text.palette.placeholder}
          aria-label={text.palette.label}
          aria-controls="command-palette-list"
          aria-activedescendant={
            matches[highlight] === undefined ? undefined : `command-${matches[highlight].id}`
          }
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              onClose();
            } else if (event.key === "ArrowDown") {
              event.preventDefault();
              setHighlight((current) => (current + 1) % Math.max(matches.length, 1));
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              setHighlight(
                (current) => (current - 1 + Math.max(matches.length, 1)) % Math.max(matches.length, 1),
              );
            } else if (event.key === "Enter") {
              event.preventDefault();
              runHighlighted();
            }
          }}
          data-testid="command-palette-input"
        />
        {matches.length === 0 ? (
          <p className="ci-palette__empty" data-testid="command-palette-empty">
            {text.palette.noMatch}
          </p>
        ) : (
          <ul className="ci-palette__list" role="listbox" id="command-palette-list">
            {matches.map((command, index) => (
              <li
                key={command.id}
                id={`command-${command.id}`}
                role="option"
                aria-selected={index === highlight}
                className={`ci-palette__item${index === highlight ? " ci-palette__item--active" : ""}`}
                onMouseEnter={() => setHighlight(index)}
                onClick={() => {
                  onClose();
                  command.run();
                }}
                data-testid={`command-${command.id}`}
              >
                <span className="ci-palette__category">{command.category}</span>
                <span className="ci-palette__title">{command.title}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
