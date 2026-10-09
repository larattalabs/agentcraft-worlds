#!/usr/bin/env node
// The macOS launcher moved to tools/unix.mjs (macOS and Linux). This shim keeps `node tools/mac.mjs ...`
// working for scripts, docs and habits: same arguments, same output, same exit code.
await import('./unix.mjs');
