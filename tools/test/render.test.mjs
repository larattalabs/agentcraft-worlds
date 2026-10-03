// node --test tools/test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { renderStructure } from '../blueprints/render.mjs';
import { buildScene, renderIso } from '../blueprints/lib/render.mjs';
import { encodePng, decodePng, crc32 } from '../blueprints/lib/png.mjs';
import { resolveMaterial } from '../blueprints/lib/colors.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'render-'));
const distinctColours = (data) => { const s = new Set(); for (let i = 0; i < data.length; i += 4) s.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]); return s.size; };

test('png: encode/decode round trip and crc32 check value', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
  const px = new Uint8Array([255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 255, 255, 9, 9, 9, 0]);
  const png = encodePng(2, 2, px);
  assert.deepEqual([...png.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const back = decodePng(png);
  assert.equal(back.width, 2);
  assert.deepEqual([...back.data], [...px]);
});

for (const id of ['workshop', 'studio']) {
  test(`render: bundled ${id} writes four non-trivial previews`, () => {
    const out = tmp();
    const r = renderStructure(id, { out });
    assert.deepEqual(Object.keys(r.files).sort(), ['cutaway', 'front', 'iso', 'top']);
    const imgs = {};
    for (const [k, f] of Object.entries(r.files)) {
      assert.ok(fs.existsSync(f), f);
      assert.ok(fs.statSync(f).size > 5000, `${k} png is suspiciously small`);
      imgs[k] = decodePng(fs.readFileSync(f));
      assert.ok(distinctColours(imgs[k].data) > 20, `${k} looks like a single colour`);
    }
    assert.ok(imgs.iso.width >= 1100 && imgs.iso.width <= 1300, `iso width ${imgs.iso.width}`);
    assert.ok(imgs.iso.height > 300);
    assert.notDeepEqual(imgs.cutaway.data, imgs.iso.data, 'cutaway must differ from iso');
    assert.equal(r.unknown.length, 0, `unknown blocks: ${r.unknown}`);
  });
}

test('render: the front option turns the building (north-front view differs from south-front)', () => {
  const out = tmp();
  const a = renderStructure('workshop', { out, cutaway: false });
  const sc = tmp();
  const repo = path.resolve(import.meta.dirname, '..', '..');
  const sidecar = JSON.parse(fs.readFileSync(path.join(repo, 'mod/src/main/resources/data/agentcraft/blueprints/workshop.blueprint.json'), 'utf8'));
  sidecar.front = 'north';
  const sp = path.join(sc, 'workshop.blueprint.json');
  fs.writeFileSync(sp, JSON.stringify(sidecar));
  const b = renderStructure('workshop', { out: sc, sidecar: sp, cutaway: false });
  assert.notDeepEqual(fs.readFileSync(a.files.iso), fs.readFileSync(b.files.iso));
});

test('render: unknown block ids get a name-derived colour and are reported, not fatal', () => {
  const unknown = new Set();
  const glassy = resolveMaterial('minecraft:cyan_stained_glass', {}, unknown);
  assert.ok(glassy.alpha < 1);
  assert.equal(resolveMaterial('minecraft:granite_stairs', {}, unknown).shape, 'stairs');
  assert.deepEqual([...unknown].sort(), ['minecraft:cyan_stained_glass', 'minecraft:granite_stairs']);
});

test('render: 100k blocks in well under 2 s', () => {
  const N = 47;
  const palette = [{ id: 'minecraft:stone_bricks' }, { id: 'minecraft:glass' }, { id: 'minecraft:oak_planks' }, { id: 'minecraft:brick_stairs', properties: { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' } }];
  const blocks = [];
  for (let y = 0; y < N; y++) for (let z = 0; z < N; z++) for (let x = 0; x < N; x++) blocks.push({ pos: [x, y, z], state: (x * 7 + y * 3 + z) % 4 });
  const structure = { size: [N, N, N], palette, blocks };
  const t0 = performance.now();
  const img = renderIso(buildScene(structure), {});
  const ms = performance.now() - t0;
  assert.ok(blocks.length > 100000);
  assert.ok(ms < 2000, `${ms.toFixed(0)} ms`);
  assert.ok(distinctColours(img.data) > 20);
});
