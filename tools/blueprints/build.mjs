#!/usr/bin/env node
// node tools/blueprints/build.mjs [id... | --all]   (no ids or --all: every design)
// Builds designs/<id>.mjs (default export = () => Blueprint) into the mod's bundled resources, checks them and
// renders the previews (render.mjs) next to the sidecar:
//   mod/src/main/resources/data/agentcraft/structure/<id>.nbt
//   mod/src/main/resources/data/agentcraft/blueprints/<id>.blueprint.json
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { writeBlueprint } from './lib/write.mjs';
import { checkFiles } from './lib/check.mjs';
import { renderStructure } from './render.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
export const NBT_DIR = path.join(REPO, 'mod/src/main/resources/data/agentcraft/structure');
export const JSON_DIR = path.join(REPO, 'mod/src/main/resources/data/agentcraft/blueprints');
const DESIGNS = path.join(HERE, 'designs');

export function listDesigns() {
  return fs.readdirSync(DESIGNS).filter((f) => f.endsWith('.mjs') && !f.startsWith('_')).map((f) => f.slice(0, -4)).sort();
}

export async function buildDesign(name, { nbtDir = NBT_DIR, jsonDir = JSON_DIR } = {}) {
  const mod = await import(pathToFileURL(path.join(DESIGNS, `${name}.mjs`)).href);
  const bp = mod.default();
  if (bp.id !== name) throw new Error(`design ${name}.mjs builds id '${bp.id}' (must match the file name)`);
  const out = writeBlueprint(bp, nbtDir, jsonDir);
  const result = checkFiles(out.nbtPath, out.jsonPath);
  return { bp, out, result };
}

async function main() {
  const ids = process.argv.slice(2).filter((a) => a !== '--all');
  const names = ids.length ? ids : listDesigns();
  let failed = false;
  for (const name of names) {
    if (!fs.existsSync(path.join(DESIGNS, `${name}.mjs`))) {
      console.error(`no design '${name}' (have: ${listDesigns().join(', ')})`);
      process.exit(2);
    }
    const { bp, out, result } = await buildDesign(name);
    const rel = (p) => path.relative(REPO, p);
    console.log(`${name}: ${bp.size.x}x${bp.size.y}x${bp.size.z}, ${out.blocks} blocks, ${out.bytes} bytes gz, ${Object.keys(bp.anchors).length} anchors`);
    console.log(`  ${rel(out.nbtPath)}\n  ${rel(out.jsonPath)}`);
    for (const w of result.warnings) console.log(`  warning: ${w}`);
    if (result.ok) {
      console.log('  check: OK');
      // previews live next to the sidecar so the mod's hub can show them (<id>.preview-{iso,cutaway,top,front}.png)
      const r = renderStructure(out.nbtPath, { out: JSON_DIR, sidecar: out.jsonPath });
      console.log(`  previews: ${Object.keys(r.files).join(', ')} (${r.ms.toFixed(0)} ms)`);
      if (r.unknown?.length) console.log(`  render: colour guessed from the name for ${r.unknown.join(', ')} (add them to lib/colors.mjs)`);
    }
    else {
      failed = true;
      console.log(`  check: FAILED (${result.errors.length})`);
      for (const e of result.errors) console.log(`    - ${e}`);
    }
  }
  if (failed) process.exit(1);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
