// Shared plumbing handed to every subsystem: the store, an outbound-message sink and a clock.
import type { Outbound } from './protocol.js';
import type { Store } from './store.js';

export interface Logger {
  info(msg: string): void;
  warn(msg: string): void;
  error(msg: string): void;
  debug(msg: string): void;
}

export interface Ctx {
  store: Store;
  /** Broadcast a protocol message to all connected clients. */
  emit(msg: Outbound): void;
  now(): number;
  log: Logger;
  /** Cut every known secret out of a text (redact.ts); identity when absent. */
  redact?: (text: string) => string;
}

export function consoleLogger(prefix = 'foreman', opts: { debug?: boolean; quiet?: boolean } = {}): Logger {
  const stamp = () => new Date().toTimeString().slice(0, 8); // local time
  return {
    info: (m) => {
      if (!opts.quiet) console.log(`${stamp()} [${prefix}] ${m}`);
    },
    warn: (m) => {
      if (!opts.quiet) console.warn(`${stamp()} [${prefix}] WARN ${m}`);
    },
    error: (m) => console.error(`${stamp()} [${prefix}] ERROR ${m}`),
    debug: (m) => {
      if (opts.debug) console.log(`${stamp()} [${prefix}] debug ${m}`);
    },
  };
}

/** A logger that redacts every line before `base` sees it. */
export function redactingLogger(base: Logger, redact: (s: string) => string): Logger {
  return {
    info: (m) => base.info(redact(m)),
    warn: (m) => base.warn(redact(m)),
    error: (m) => base.error(redact(m)),
    debug: (m) => base.debug(redact(m)),
  };
}

export const silentLogger: Logger = { info() {}, warn() {}, error() {}, debug() {} };
