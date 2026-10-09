// A minimal client for `codex app-server`: JSON-RPC 2.0 messages (without the "jsonrpc" field),
// one JSON object per line on stdio. The server sends notifications and also requests of its own
// (approvals, dynamic tool calls), which `onRequest` answers.
import { spawn, type ChildProcess } from 'node:child_process';
import { lineSplitter } from '../../util/lines.js';
import readline from 'node:readline';

type Json = unknown;
type Pending = { resolve(v: Json): void; reject(e: Error): void; method: string };

export class RpcError extends Error {
  constructor(
    readonly method: string,
    readonly code: number | undefined,
    message: string,
  ) {
    super(`${method}: ${message}`);
  }
}

export interface AppServerOptions {
  cwd: string;
  env: NodeJS.ProcessEnv;
  /** extra CLI args before `app-server` subcommand args (e.g. `-c key=value`) */
  args?: string[];
  /** each whole stderr line (an endless one is dropped, never cut) */
  onStderr?(line: string): void;
}

export class AppServer {
  readonly child: ChildProcess;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private notificationHandlers: Array<(method: string, params: Json) => void> = [];
  private requestHandler: ((method: string, params: Json) => Promise<Json>) | undefined;
  private exitError: Error | undefined;
  private stderrTail: string[] = [];
  /** settles when the process has exited */
  readonly exited: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;

  constructor(bin: string, opts: AppServerOptions) {
    // a .cmd/.bat shim (npm's global install on Windows) only runs through cmd.exe
    const viaCmd = process.platform === 'win32' && /\.(cmd|bat)$/i.test(bin);
    const args = [...(opts.args ?? []), 'app-server'];
    this.child = viaCmd
      ? spawn(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', `"${bin}" ${args.join(' ')}`], { cwd: opts.cwd, env: opts.env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true, windowsVerbatimArguments: true })
      : spawn(bin, args, { cwd: opts.cwd, env: opts.env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.child.stdin?.on('error', () => undefined); // EPIPE after exit: the exit handler reports it
    this.child.stderr?.setEncoding('utf8');
    // whole lines in the tail (a line split across two chunks stays one line, so a secret in it is
    // still redacted whole when an error quotes it)
    const lines = lineSplitter((line) => {
      if (!line.trim()) return;
      this.stderrTail.push(line);
      if (this.stderrTail.length > 20) this.stderrTail.shift();
      opts.onStderr?.(line);
    });
    this.child.stderr?.on('data', (s: string) => lines.push(s));
    this.child.stderr?.on('end', () => lines.end());
    this.exited = new Promise((resolve) => {
      this.child.once('error', (e) => {
        this.fail(new Error(`could not start codex: ${e.message}`));
        resolve({ code: null, signal: null });
      });
      this.child.once('exit', (code, signal) => {
        this.fail(new Error(`codex app-server exited (${signal ?? code})${this.stderrTail.length ? `: ${this.stderrTail.slice(-3).join(' | ')}` : ''}`));
        resolve({ code, signal });
      });
    });
    const rl = readline.createInterface({ input: this.child.stdout! });
    rl.on('line', (line) => this.onLine(line));
  }

  /** The last lines the server wrote to stderr (for error messages). */
  stderr(): string[] {
    return [...this.stderrTail];
  }

  onNotification(handler: (method: string, params: Json) => void): void {
    this.notificationHandlers.push(handler);
  }

  onRequest(handler: (method: string, params: Json) => Promise<Json>): void {
    this.requestHandler = handler;
  }

  request<T = Json>(method: string, params: Json, timeoutMs = 60_000): Promise<T> {
    if (this.exitError) return Promise.reject(this.exitError);
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new RpcError(method, undefined, `no answer after ${Math.round(timeoutMs / 1000)}s`));
      }, timeoutMs);
      timer.unref?.();
      this.pending.set(id, {
        method,
        resolve: (v) => {
          clearTimeout(timer);
          resolve(v as T);
        },
        reject: (e) => {
          clearTimeout(timer);
          reject(e);
        },
      });
      this.write({ id, method, params });
    });
  }

  notify(method: string, params?: Json): void {
    this.write(params === undefined ? { method } : { method, params });
  }

  /** End the process (the team also ends its whole process tree when a turn is stopped). */
  close(): void {
    try {
      this.child.stdin?.end();
    } catch {
      /* ignore */
    }
    if (this.child.exitCode === null && this.child.signalCode === null) this.child.kill();
  }

  private write(msg: Json): void {
    if (this.exitError || !this.child.stdin?.writable) return;
    this.child.stdin.write(`${JSON.stringify(msg)}\n`);
  }

  private fail(e: Error): void {
    this.exitError ??= e;
    for (const p of this.pending.values()) p.reject(e);
    this.pending.clear();
  }

  private onLine(line: string): void {
    let m: { id?: number | string; method?: string; params?: Json; result?: Json; error?: { code?: number; message?: string } };
    try {
      m = JSON.parse(line);
    } catch {
      return; // not a protocol line
    }
    if (m.method === undefined && m.id !== undefined) {
      const p = this.pending.get(Number(m.id));
      if (!p) return;
      this.pending.delete(Number(m.id));
      if (m.error) p.reject(new RpcError(p.method, m.error.code, m.error.message ?? 'error'));
      else p.resolve(m.result);
      return;
    }
    if (m.method !== undefined && m.id !== undefined) {
      const id = m.id;
      const method = m.method;
      const handler = this.requestHandler;
      if (!handler) {
        this.write({ id, error: { code: -32601, message: `unsupported: ${method}` } });
        return;
      }
      handler(method, m.params)
        .then((result) => this.write({ id, result }))
        .catch((e: Error) => this.write({ id, error: { code: -32000, message: e.message } }));
      return;
    }
    if (m.method !== undefined) for (const h of this.notificationHandlers) h(m.method, m.params);
  }
}
