// Whole lines out of a chunked stream (a child's stderr), so a secret split across two chunks is
// still one line when it is redacted. A line longer than `max` is dropped whole, through its newline:
// never cut in pieces (a piece of a secret would not be recognised).

export interface LineSplitter {
  push(chunk: string): void;
  /** the stream ended: the last unterminated line, unless it was being dropped */
  end(): void;
}

export function lineSplitter(onLine: (line: string) => void, max = 64_000, onDropped?: () => void): LineSplitter {
  let pending = '';
  let dropping = false;
  return {
    push(chunk: string) {
      let text = chunk;
      if (dropping) {
        const nl = text.indexOf('\n');
        if (nl < 0) return;
        dropping = false;
        text = text.slice(nl + 1);
      }
      pending += text;
      const lines = pending.split('\n');
      pending = lines.pop() ?? '';
      for (const l of lines) onLine(l.replace(/\r$/, ''));
      if (pending.length > max) {
        pending = '';
        dropping = true;
        onDropped?.();
      }
    },
    end() {
      if (!dropping && pending) onLine(pending.replace(/\r$/, ''));
      pending = '';
      dropping = false;
    },
  };
}
