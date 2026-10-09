// Whole lines out of a chunked stream (a child's stderr), so a secret split across two chunks is
// still one line when it is redacted. A line longer than `max` characters is dropped whole, through
// its newline, however the stream was chunked: never cut in pieces (a piece of a secret would not be
// recognised).

export interface LineSplitter {
  push(chunk: string): void;
  /** the stream ended: the last unterminated line, unless it was too long */
  end(): void;
}

export function lineSplitter(onLine: (line: string) => void, max = 64_000, onDropped?: () => void): LineSplitter {
  let pending = '';
  /** the current line is over `max`: its text is skipped up to its newline */
  let dropping = false;
  const finish = (line: string) => {
    if (dropping || line.length > max) {
      dropping = false;
      onDropped?.();
      return;
    }
    onLine(line.replace(/\r$/, ''));
  };
  return {
    push(chunk: string) {
      const parts = chunk.split('\n');
      const last = parts.pop()!;
      for (const p of parts) {
        finish(dropping ? '' : pending + p);
        pending = '';
      }
      if (dropping) return;
      pending += last;
      if (pending.length > max) {
        pending = '';
        dropping = true;
      }
    },
    end() {
      if (dropping) onDropped?.();
      else if (pending) onLine(pending.replace(/\r$/, ''));
      pending = '';
      dropping = false;
    },
  };
}
