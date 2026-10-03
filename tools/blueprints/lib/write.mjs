// Writes <id>.nbt (gzipped vanilla structure template) + <id>.blueprint.json (sidecar).
import fs from 'node:fs';
import path from 'node:path';
import { encodeGzip } from './nbt.mjs';

/**
 * @param {import('./kit.mjs').Blueprint} bp
 * @param {string} nbtDir   where <id>.nbt goes
 * @param {string} [jsonDir] where <id>.blueprint.json goes (default: nbtDir)
 * @returns {{nbtPath:string, jsonPath:string, blocks:number, bytes:number}}
 */
export function writeBlueprint(bp, nbtDir, jsonDir = nbtDir) {
  fs.mkdirSync(nbtDir, { recursive: true });
  fs.mkdirSync(jsonDir, { recursive: true });
  const nbtPath = path.join(nbtDir, `${bp.id}.nbt`);
  const jsonPath = path.join(jsonDir, `${bp.id}.blueprint.json`);
  const bytes = encodeGzip(bp.toStructure());
  fs.writeFileSync(nbtPath, bytes);
  fs.writeFileSync(jsonPath, `${JSON.stringify(bp.sidecar(), null, 2)}\n`);
  return { nbtPath, jsonPath, blocks: bp.cells.size, bytes: bytes.length };
}
