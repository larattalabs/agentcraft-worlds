// Where a JSON text stops being valid, as line and column - without quoting any of it. JSON.parse's
// own messages can contain a slice of the input (a secret in config.json, say), so they never go to
// a client; this small scanner finds the position instead.

/** 1-based line and column of the first JSON syntax error in `text`, or undefined when it parses. */
export function jsonErrorPosition(text: string): { line: number; column: number } | undefined {
  let i = 0;
  const fail = (): never => {
    throw new Error('x');
  };
  const ws = () => {
    while (i < text.length && ' \t\n\r'.includes(text[i]!)) i++;
  };
  const lit = (word: string) => {
    for (const ch of word) if (text[i++] !== ch) (i--, fail());
  };
  const str = () => {
    if (text[i] !== '"') fail();
    i++;
    while (i < text.length && text[i] !== '"') {
      const c = text.charCodeAt(i);
      if (c < 0x20) fail();
      if (text[i] === '\\') {
        i++;
        const e = text[i];
        if (e === 'u') {
          if (!/^[0-9a-fA-F]{4}$/.test(text.slice(i + 1, i + 5))) fail();
          i += 4;
        } else if (e === undefined || !'"\\/bfnrt'.includes(e)) fail();
      }
      i++;
    }
    if (text[i] !== '"') fail();
    i++;
  };
  const num = () => {
    const m = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i));
    if (!m) fail();
    i += m![0].length;
  };
  const value = (depth: number): void => {
    if (depth > 512) fail();
    ws();
    const c = text[i];
    if (c === '{') {
      i++;
      ws();
      if (text[i] === '}') return void i++;
      for (;;) {
        ws();
        str();
        ws();
        if (text[i] !== ':') fail();
        i++;
        value(depth + 1);
        ws();
        if (text[i] === ',') i++;
        else if (text[i] === '}') return void i++;
        else fail();
      }
    }
    if (c === '[') {
      i++;
      ws();
      if (text[i] === ']') return void i++;
      for (;;) {
        value(depth + 1);
        ws();
        if (text[i] === ',') i++;
        else if (text[i] === ']') return void i++;
        else fail();
      }
    }
    if (c === '"') return str();
    if (c === 't') return lit('true');
    if (c === 'f') return lit('false');
    if (c === 'n') return lit('null');
    return num();
  };
  try {
    value(0);
    ws();
    if (i < text.length) fail();
    return undefined;
  } catch {
    const before = text.slice(0, Math.min(i, text.length));
    const lines = before.split('\n');
    return { line: lines.length, column: lines[lines.length - 1]!.length + 1 };
  }
}

/** "<what> is not valid JSON at line L, column C" (no excerpt of the text). */
export function jsonErrorMessage(what: string, text: string): string {
  const p = jsonErrorPosition(text);
  return p ? `${what} is not valid JSON at line ${p.line}, column ${p.column}` : `${what} is not valid JSON`;
}
