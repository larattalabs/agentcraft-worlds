// Blueprint kit: build an AgentCraft office as code. See docs/BUILDINGS.md for the contract and
// tools/blueprints/README.md for the API overview.
//
// Conventions (same as vanilla structure templates): the template origin is its minimum corner,
// +x = east, +y = up, +z = south. `front: 'south'` means the entrance faces +z.
// Anchors: spots = feet position (x+.5, y, z+.5); block anchors = centre of the surface; yaw in
// Minecraft degrees: 0 = facing south (+z), 90 = west, 180 = north, -90 = east.
// Floor convention used by the designs: floor row y = groundY-1 (replaces the terrain's top block),
// feet row y = groundY (sits on the terrain surface).
import { nbt } from './nbt.mjs';
import { normalize, qualify, isCube } from './blocks.mjs';

/** DataVersion of Minecraft 26.3 (version.json world_version in the 26.3 jar). */
export const DATA_VERSION = 5023;

/**
 * Block ids (shorthand for designs).
 *
 * Materials (docs/BUILDINGS.md "Materials", contract C5): structure, floors, walls, roofs, trim and light are VANILLA
 * blocks, so a world opened without the mod keeps its buildings (only the station blocks go missing). The names keep
 * the AgentCraft look they stand for: plaster = smooth quartz, plaster frame = calcite, walnut = stripped dark oak
 * log, walnut trim = dark oak planks, tile = terracotta, parquet = oak planks, glow panel = ochre froglight.
 * AgentCraft blocks only where they are functional: monitor, task board, decision podium, console terminal, status
 * lamp, merge station, memory archive / catalog. The checker refuses the decorative AgentCraft blocks.
 */
export const B = {
  air: 'minecraft:air',
  plaster: 'minecraft:smooth_quartz',
  plasterFrame: 'minecraft:calcite',
  walnut: 'minecraft:stripped_dark_oak_log',
  walnutTrim: 'minecraft:dark_oak_planks',
  tile: 'minecraft:terracotta',
  parquet: 'minecraft:oak_planks',
  glowPanel: 'minecraft:ochre_froglight',
  seaLantern: 'minecraft:sea_lantern',
  glowStrip: 'minecraft:end_rod', // legacy name (older designs): a strip light is an end rod now
  button: 'minecraft:stone_button',
  monitor: 'agentcraft:monitor',
  taskBoard: 'agentcraft:task_board',
  podium: 'agentcraft:decision_podium',
  archive: 'agentcraft:memory_archive',
  catalog: 'agentcraft:memory_catalog',
  mergeStation: 'agentcraft:merge_station',
  statusLamp: 'agentcraft:status_lamp',
  console: 'agentcraft:console_terminal',
  pane: 'minecraft:glass_pane',
  glass: 'minecraft:glass',
  deskSlab: 'minecraft:dark_oak_slab',
  chairStairs: 'minecraft:dark_oak_stairs',
  sofaStairs: 'minecraft:brown_wool_stairs',
  meetingStairs: 'minecraft:birch_stairs',
  door: 'minecraft:iron_door',
  light: 'minecraft:light',
  lantern: 'minecraft:lantern',
  candle: 'minecraft:candle',
  plant: 'minecraft:potted_fern',
};

export const DIR = {
  north: { dx: 0, dz: -1, yaw: 180 },
  south: { dx: 0, dz: 1, yaw: 0 },
  west: { dx: -1, dz: 0, yaw: 90 },
  east: { dx: 1, dz: 0, yaw: -90 },
};
export const OPPOSITE = { north: 'south', south: 'north', west: 'east', east: 'west' };
export const yawOf = (dir) => DIR[dir].yaw;
/** Horizontal direction name a sitter looks in for a yaw (multiples of 90). */
export function dirOfYaw(yaw) {
  const y = ((Math.round(yaw / 90) * 90) % 360 + 360) % 360;
  return { 0: 'south', 90: 'west', 180: 'north', 270: 'east' }[y];
}
/** Yaw looking from (x,z) towards (tx,tz) (Minecraft convention). */
export const lookYaw = (x, z, tx, tz) => (Math.atan2(-(tx - x), tz - z) * 180) / Math.PI;
const r3 = (n) => Math.round(n * 1000) / 1000 || 0; // || 0 folds -0
const key = (x, y, z) => `${x},${y},${z}`;

/** Cells of the axis-aligned segment/rect (x0,z0)-(x1,z1), row-major. */
export function cellsOf(x0, z0, x1, z1) {
  const out = [];
  for (let z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) for (let x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) out.push([x, z]);
  return out;
}

export class Blueprint {
  /**
   * @param {{id:string,name?:string,description?:string,kind?:'single'|'group',wings?:number,
   *   size:number[]|{x:number,y:number,z:number},groundY?:number,front?:string,materials?:string,
   *   foundationBlock?:string,walk?:number[]|object,approach?:false|{length?:number,width?:number,block?:string,slab?:string}}} o
   * `materials`: 'agentcraft' (default) = the AgentCraft look built from vanilla blocks (the B palette);
   * 'vanilla' = any vanilla look. Both use AgentCraft blocks only for the station blocks.
   * `foundationBlock`: the vanilla block the mod fills under the floor down to the ground on placement (contract C4).
   * `approach`: the entrance approach the mod builds in front of the door on placement (docs/BUILDINGS.md "Entrance
   * approach"): rows out from the box, cells across, the path block and the half-step slab; false = none.
   */
  constructor(o) {
    if (!/^[a-z0-9_]+$/.test(o.id ?? '')) throw new Error(`blueprint id must match [a-z0-9_]+ (got '${o.id}')`);
    this.id = o.id;
    this.name = o.name ?? o.id;
    this.description = o.description ?? '';
    this.kind = o.kind ?? 'single';
    this.wings = o.wings ?? 1;
    const s = Array.isArray(o.size) ? o.size : [o.size.x, o.size.y, o.size.z];
    this.size = { x: s[0], y: s[1], z: s[2] };
    this.groundY = o.groundY ?? 1;
    this.front = o.front ?? 'south';
    this.materials = o.materials ?? 'agentcraft';
    this.foundationBlock = o.foundationBlock ?? 'minecraft:stone_bricks';
    this.approach = o.approach === false ? { length: 0, width: 3, block: 'minecraft:dirt_path', slab: 'minecraft:stone_brick_slab' }
      : { length: 6, width: 3, block: 'minecraft:dirt_path', slab: 'minecraft:stone_brick_slab', ...(o.approach ?? {}) };
    this.cells = new Map(); // "x,y,z" -> { state:{name,props}, nbt }
    this.anchors = {};
    // `origin` shifts every design coordinate (set/get/anchor/walk) so a design can be written relative to its
    // main walls while the template keeps a margin for overhangs, porches and paths.
    const og = o.origin ?? [0, 0, 0];
    this.ox = og[0];
    this.oy = og[1];
    this.oz = og[2];
    this.walk = null;
    if (o.walk) this.setWalk(o.walk);
  }

  /** Feet row (rows >= this are the building above the terrain). */
  get feet() { return this.groundY; }

  setWalk(w) {
    const o = Array.isArray(w)
      ? { minX: w[0], minY: w[1], minZ: w[2], maxX: w[3], maxY: w[4], maxZ: w[5] }
      : { minX: w.minX, minY: w.minY, minZ: w.minZ, maxX: w.maxX, maxY: w.maxY, maxZ: w.maxZ };
    this.walk = { minX: o.minX + this.ox, minY: o.minY + this.oy, minZ: o.minZ + this.oz, maxX: o.maxX + this.ox, maxY: o.maxY + this.oy, maxZ: o.maxZ + this.oz };
    return this;
  }

  // ------------------------------------------------------------------ cells

  inBounds(x, y, z) {
    x += this.ox; y += this.oy; z += this.oz;
    return x >= 0 && y >= 0 && z >= 0 && x < this.size.x && y < this.size.y && z < this.size.z;
  }

  /** Place one block. `props` may be partial (the rest is filled with defaults); `nbt` is a block-entity compound as a plain object. */
  set(x, y, z, block, props = {}, nbtData = null) {
    if (!this.inBounds(x, y, z)) throw new Error(`${this.id}: set(${x},${y},${z}) outside size ${this.size.x}x${this.size.y}x${this.size.z} (origin ${this.ox},${this.oy},${this.oz})`);
    this.cells.set(key(x + this.ox, y + this.oy, z + this.oz), { state: normalize(block, props), nbt: nbtData });
    return this;
  }

  air(x, y, z) { return this.set(x, y, z, B.air); }

  get(x, y, z) { return this.cells.get(key(x + this.ox, y + this.oy, z + this.oz)) ?? null; }

  nameAt(x, y, z) { return this.get(x, y, z)?.state.name ?? null; }

  /** Attach a station binding to an existing station block. */
  bind(x, y, z, binding) {
    const c = this.get(x, y, z);
    if (!c) throw new Error(`${this.id}: bind(${x},${y},${z}): no block there`);
    c.nbt = { ...(c.nbt ?? {}), binding };
    return this;
  }

  /** Fill an inclusive box [x0,y0,z0,x1,y1,z1]. */
  fill(box, block, props = {}, nbtData = null) {
    const [x0, y0, z0, x1, y1, z1] = box;
    for (let y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
      for (let z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
        for (let x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) this.set(x, y, z, block, props, nbtData);
    return this;
  }

  /** Only the six faces of the box. */
  hollow(box, block, props = {}) {
    const [x0, y0, z0, x1, y1, z1] = box.map((n, i) => (i < 3 ? Math.min(n, box[i + 3]) : Math.max(n, box[i - 3])));
    for (let y = y0; y <= y1; y++)
      for (let z = z0; z <= z1; z++)
        for (let x = x0; x <= x1; x++)
          if (x === x0 || x === x1 || y === y0 || y === y1 || z === z0 || z === z1) this.set(x, y, z, block, props);
    return this;
  }

  /** Interior air so placing the template clears the space. */
  carve(box) { return this.fill(box, B.air); }

  /**
   * A closed room: walls on the box's faces, a floor on y0, a ceiling on y1 and air inside.
   * box = [x0,y0,z0,x1,y1,z1] including the walls.
   */
  shell(box, { wall = B.plaster, floor = B.parquet, ceiling = B.plaster, wallProps, floorProps, ceilingProps } = {}) {
    const [x0, y0, z0, x1, y1, z1] = box;
    this.carve([x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 1, z1 - 1]);
    this.hollow(box, wall, wallProps);
    this.fill([x0, y0, z0, x1, y0, z1], floor, floorProps);
    this.fill([x0, y1, z0, x1, y1, z1], ceiling, ceilingProps);
    return this;
  }

  // ------------------------------------------------------------------ architecture

  /** A wall run (axis-aligned rectangle) of one block, e.g. wall(0,1,0, 0,6,20, B.plaster). */
  wall(x0, y0, z0, x1, y1, z1, block, props) { return this.fill([x0, y0, z0, x1, y1, z1], block, props); }

  /** Glass panes over a wall rectangle (connections are computed in finalize()). */
  window(x0, y0, z0, x1, y1, z1, pane = B.pane) { return this.fill([x0, y0, z0, x1, y1, z1], pane); }

  /**
   * A two-high door in the wall cell (x,y,z); the cell above it is the door's upper half. `facing` is the side
   * the door faces (an entrance: the outside). Default: an IRON door (zombies cannot break it), written closed,
   * with a stone button on BOTH sides at y+1 on the jamb next to it (`buttonSide` +1 = the +x / +z jamb, -1 the
   * other). The jamb cell becomes `jamb` (a full, conductive block: the button powers it, it powers the door).
   * `buttons: false` for a plain door (e.g. a wooden interior door: `block: 'minecraft:dark_oak_door'`).
   */
  door(x, y, z, facing, { hinge = 'left', open = false, block = B.door, buttons = block === 'minecraft:iron_door', buttonSide = 1, jamb = B.walnut } = {}) {
    this.set(x, y, z, block, { facing, half: 'lower', hinge, open: String(open) });
    this.set(x, y + 1, z, block, { facing, half: 'upper', hinge, open: String(open) });
    if (buttons) {
      const f = DIR[facing];
      const [jx, jz] = f.dx === 0 ? [x + buttonSide, z] : [x, z + buttonSide];
      this.set(jx, y + 1, jz, jamb);
      this.set(jx + f.dx, y + 1, jz + f.dz, B.button, { face: 'wall', facing });
      this.set(jx - f.dx, y + 1, jz - f.dz, B.button, { face: 'wall', facing: OPPOSITE[facing] });
    }
    return this;
  }

  stairs(x, y, z, facing, { block = B.chairStairs, half = 'bottom', shape = 'straight' } = {}) {
    return this.set(x, y, z, block, { facing, half, shape });
  }

  slab(x, y, z, type = 'bottom', block = B.deskSlab) { return this.set(x, y, z, block, { type }); }

  /** A flat floor rectangle on row y: floor(x0,z0,x1,z1,y,block). */
  floor(x0, z0, x1, z1, y, block = B.parquet, props) { return this.fill([x0, y, z0, x1, y, z1], block, props); }

  /** A 1-cell ring on row y (e.g. a tile border). */
  floorRing(x0, z0, x1, z1, y, block = B.tile) {
    for (const [x, z] of cellsOf(x0, z0, x1, z1)) if (x === x0 || x === x1 || z === z0 || z === z1) this.set(x, y, z, block);
    return this;
  }

  rug(x0, z0, x1, z1, y, { fill = 'minecraft:white_carpet', border = 'minecraft:brown_carpet' } = {}) {
    for (const [x, z] of cellsOf(x0, z0, x1, z1)) this.set(x, y, z, x === x0 || x === x1 || z === z0 || z === z1 ? border : fill);
    return this;
  }

  /** Ceiling slab on row y plus glow_panel lights on a grid: lights at x = lx0, lx0+step.. and z = lz0, lz0+stepZ.. . */
  ceiling(x0, z0, x1, z1, y, block = B.plaster) { return this.fill([x0, y, z0, x1, y, z1], block); }

  ceilingLights(y, xs, zs, block = B.glowPanel) {
    for (const x of xs) for (const z of zs) this.set(x, y, z, block);
    return this;
  }

  /** Flat roof deck on row y (over the whole box) with an optional parapet ring on y+1. */
  roofFlat(x0, z0, x1, z1, y, { deck = B.tile, parapet = B.walnutTrim } = {}) {
    this.fill([x0, y, z0, x1, y, z1], deck);
    if (parapet) this.floorRing(x0, z0, x1, z1, y + 1, parapet);
    return this;
  }

  /**
   * A gabled (sloped) roof over the rectangle (x0,z0)-(x1,z1) (may overhang the walls), starting on row y.
   * ridge: 'x' = the ridge line runs east-west (eaves on the north/south edges).
   * pitch 1: one row per cell (stairs, ridge cap `full`); pitch 0.5 (default): half a row per cell, alternating
   * stairs / top slabs (shallow, so wide halls do not get a barn-high roof), ridge = a bottom slab.
   * The attic is left hollow. Gable ends: pass `gableInset` (cells in from the rectangle's end, normally the overhang)
   * to close each end with `gable` up to the slope at that wall plane; `gable: null` leaves them open.
   * Stairs `facing` is their tall side, so it points up the slope (towards the ridge).
   */
  roofGable(x0, z0, x1, z1, y, {
    ridge = 'x', pitch = 0.5, stairs = 'minecraft:dark_oak_stairs', slab = 'minecraft:dark_oak_slab', full = 'minecraft:dark_oak_planks',
    gable = B.plaster, gableInset = 0, gableFrom = null,
  } = {}) {
    if (pitch !== 1 && pitch !== 0.5) throw new Error('roofGable: pitch must be 1 or 0.5');
    const alongX = ridge === 'x';
    const a0 = alongX ? z0 : x0;
    const a1 = alongX ? z1 : x1;
    const b0 = alongX ? x0 : z0;
    const b1 = alongX ? x1 : z1;
    const half = (a1 - a0) / 2;
    const profile = (a) => {
      const m = Math.min(a - a0, a1 - a);
      const down = a - a0 < a1 - a ? (alongX ? 'south' : 'east') : alongX ? 'north' : 'west'; // = towards the ridge (a stair's `facing` is its tall side)
      const mid = Number.isInteger(half) && a - a0 === half;
      if (pitch === 1) return mid ? { dy: m, kind: 'ridge', down } : { dy: m, kind: 'stair', down };
      if (mid) return { dy: Math.ceil(m / 2), kind: 'ridgeSlab', down };
      return m % 2 === 0 ? { dy: m / 2, kind: 'stair', down } : { dy: (m - 1) / 2, kind: 'topSlab', down };
    };
    for (let a = a0; a <= a1; a++) {
      const p = profile(a);
      for (let b = b0; b <= b1; b++) {
        const [x, z] = alongX ? [b, a] : [a, b];
        if (p.kind === 'ridge') this.set(x, y + p.dy, z, full);
        else if (p.kind === 'ridgeSlab') this.set(x, y + p.dy, z, slab, { type: 'bottom' });
        else if (p.kind === 'topSlab') this.set(x, y + p.dy, z, slab, { type: 'top' });
        else this.set(x, y + p.dy, z, stairs, { facing: p.down, half: 'bottom', shape: 'straight' });
      }
      if (gable) {
        const top = y + p.dy - 1; // last row to fill (the roof block itself sits at y+dy)
        for (const b of [b0 + gableInset, b1 - gableInset]) {
          const [x, z] = alongX ? [b, a] : [a, b];
          for (let yy = gableFrom ?? y; yy <= top; yy++) this.set(x, yy, z, gable);
        }
      }
    }
    return this;
  }

  /**
   * A hip roof with a flat top: stairs rise one row per cell from all four edges for `rise` rows (corners use
   * outer-corner stairs, the slope is backed with plaster), then a full-block plateau `top` at y+rise, with
   * optional `skylights = [[x0,z0,x1,z1],...]` replaced by glass. The footprint must be wider than 2*rise.
   */
  roofHip(x0, z0, x1, z1, y, { rise = 3, stairs = 'minecraft:dark_oak_stairs', top = B.tile, skylights = [], glass = B.glass, under = B.plaster } = {}) {
    if (x1 - x0 + 1 <= 2 * rise || z1 - z0 + 1 <= 2 * rise) throw new Error('roofHip: footprint too small for the rise');
    for (let z = z0; z <= z1; z++) {
      for (let x = x0; x <= x1; x++) {
        const dN = z - z0;
        const dS = z1 - z;
        const dW = x - x0;
        const dE = x1 - x;
        const m = Math.min(dW, dE, dN, dS);
        if (m >= rise) { this.set(x, y + rise, z, top); continue; }
        const sides = [dN === m && 'north', dS === m && 'south', dW === m && 'west', dE === m && 'east'].filter(Boolean);
        let facing = { north: 'south', south: 'north', west: 'east', east: 'west' }[sides[0]]; // tall side faces the plateau
        let shape = 'straight';
        if (sides.length > 1) {
          const ns = sides.find((q) => q === 'north' || q === 'south');
          const we = sides.find((q) => q === 'west' || q === 'east');
          facing = ns === 'north' ? 'south' : 'north';
          shape = { 'north,west': 'outer_left', 'north,east': 'outer_right', 'south,east': 'outer_left', 'south,west': 'outer_right' }[`${ns},${we}`];
        }
        this.set(x, y + m, z, stairs, { facing, half: 'bottom', shape });
        if (under) for (let yy = y; yy < y + m; yy++) this.set(x, yy, z, under);
      }
    }
    for (const [sx0, sz0, sx1, sz1] of skylights) this.fill([sx0, y + rise, sz0, sx1, y + rise, sz1], glass);
    return this;
  }

  /** A vertical post of `block` from y0..y1 at (x,z). */
  post(x, z, y0, y1, block = B.walnut) { return this.fill([x, y0, z, x, y1, z], block); }

  /** A flat awning/porch roof of slabs on row y over (x0,z0)-(x1,z1) with posts (feet row up to y-1) at `posts` = [[x,z],...]. */
  awning(x0, z0, x1, z1, y, { deck = 'minecraft:dark_oak_slab', posts = [], post = B.walnut, feetY = this.feet } = {}) {
    for (const [x, z] of cellsOf(x0, z0, x1, z1)) this.slab(x, y, z, 'bottom', deck);
    for (const [px, pz] of posts) this.fill([px, feetY, pz, px, y - 1, pz], post);
    return this;
  }

  // ------------------------------------------------------------------ lights, decor

  /** A flush light block (ochre froglight; e.g. in a ceiling row or a wall band). */
  glowPanel(x, y, z) { return this.set(x, y, z, B.glowPanel); }

  /**
   * A small strip light: an end rod sticking out of the wall towards `facing` (light 14). Prefer a light band in
   * a wall/header (glowPanel) or lanterns; end rods suit porches and shelves.
   */
  glowStrip(x, y, z, facing) { return this.set(x, y, z, 'minecraft:end_rod', { facing }); }

  /**
   * Invisible light source block (no collision). The checker does NOT count it as light (rooms are lit by visible
   * sources), so only use it for effects.
   */
  invisibleLight(x, y, z, level = 15) { return this.set(x, y, z, B.light, { level }); }

  plant(x, y, z) { return this.set(x, y, z, B.plant); }

  lantern(x, y, z, hanging = false) { return this.set(x, y, z, B.lantern, { hanging: String(hanging) }); }

  candle(x, y, z, n = 2, lit = true) { return this.set(x, y, z, B.candle, { candles: n, lit: String(lit) }); }

  /** A status lamp station block bound to `binding` ("ci:#1", "goal", "merge", "agent:kit"...). */
  statusLamp(x, y, z, binding, status = 'idle') {
    return this.set(x, y, z, B.statusLamp, { status }, { binding });
  }

  /**
   * A status lamp set into a wall cell, facing `facing` (into the room). When the cell behind it is outside the
   * building (unwritten), it gets `backing` (a small dark-oak plate), so a world without the mod has no hole in the
   * shell (C5).
   */
  wallLamp(x, y, z, facing, binding, status = 'idle', { backing = B.walnutTrim } = {}) {
    this.statusLamp(x, y, z, binding, status);
    const f = DIR[facing];
    const bx = x - f.dx;
    const bz = z - f.dz;
    // only an UNWRITTEN cell is outside (rooms are carved to explicit air): a lamp in an inner partition stays as is
    if (!this.get(bx, y, bz) && this.inBounds(bx, y, bz)) this.set(bx, y, bz, backing);
    return this;
  }

  // ------------------------------------------------------------------ anchors

  /** Raw anchor (world-relative template coordinates; spots = feet position). */
  anchor(name, x, y, z, yaw = 0, pitch = 0) {
    this.anchors[name] = { x: r3(x + this.ox), y: r3(y + this.oy), z: r3(z + this.oz), yaw: r3(yaw), pitch: r3(pitch) };
    return this;
  }

  /** Next free slot name of a station: station, station_2, station_3, ... */
  slotName(station) {
    let n = 1;
    while (this.anchors[n === 1 ? station : `${station}_${n}`]) n++;
    return n === 1 ? station : `${station}_${n}`;
  }

  /** Standing spot for station (adds the next slot). (cx, cz) are cell indices (the spot is the cell centre) unless `exact`. */
  spot(station, cx, cz, yaw, { y = this.feet, exact = false } = {}) {
    const name = this.slotName(station);
    this.anchor(name, exact ? cx : cx + 0.5, y, exact ? cz : cz + 0.5, yaw, 0);
    return name;
  }

  /** QA camera `cam_<name>` (eye position) looking at a point. */
  camera(name, eye, lookAt) {
    const n = name.startsWith('cam_') ? name : `cam_${name}`;
    const [ex, ey, ez] = eye;
    const [lx, ly, lz] = lookAt;
    const dx = lx - ex;
    const dy = ly - ey;
    const dz = lz - ez;
    const yaw = (Math.atan2(-dx, dz) * 180) / Math.PI;
    const pitch = (-Math.atan2(dy, Math.hypot(dx, dz)) * 180) / Math.PI;
    return this.anchor(n, ex, ey, ez, yaw, pitch);
  }

  // ------------------------------------------------------------------ furniture kits

  /**
   * A worker desk against a wall: 3 desk slabs, a 3x2 monitor above them bound to the agent, a chair
   * at the left third of the bay. (x, z) is the centre cell of the desk row (the cell next to the wall),
   * `facing` is the direction the monitor faces (towards the room, i.e. away from the wall).
   * Writes anchors desk_<id>, seat_<id> (the chair cell, facing the monitor) and monitor_<id>.
   */
  desk(x, z, facing, agentId, { y = this.feet, chair = B.chairStairs, slab = B.deskSlab } = {}) {
    const f = DIR[facing];
    const lat = { dx: -f.dz, dz: f.dx };
    for (let i = -1; i <= 1; i++) {
      const cx = x + lat.dx * i;
      const cz = z + lat.dz * i;
      this.set(cx, y, cz, slab, { type: 'top' });
      for (let h = 1; h <= 2; h++) this.set(cx, y + h, cz, B.monitor, { facing, lit: 'true' }, { binding: agentId });
    }
    const sx = x + f.dx + lat.dx;
    const sz = z + f.dz + lat.dz;
    this.set(sx, y, sz, chair, { facing, half: 'bottom', shape: 'straight' });
    const yaw = yawOf(OPPOSITE[facing]);
    this.anchor(`desk_${agentId}`, sx + 0.5, y, sz + 0.5, yaw);
    this.anchor(`seat_${agentId}`, sx + 0.5, y, sz + 0.5, yaw);
    // screen surface: 0.25 in from the wall side of the cell
    const mx = f.dx !== 0 ? x + (f.dx > 0 ? 0.252 : 1 - 0.252) : x + 0.5;
    const mz = f.dz !== 0 ? z + (f.dz > 0 ? 0.252 : 1 - 0.252) : z + 0.5;
    this.anchor(`monitor_${agentId}`, mx, y + 2.0, mz, yawOf(facing));
    return this;
  }

  /**
   * A wide task board on a wall: boards over the segment (x0,z0)-(x1,z1) (one cell thick, cells next to the
   * wall), `height` rows high above a walnut sill, with a header and walnut posts, each cell bound
   * `repo:#<wing>`. `facing` is the direction the board faces. Anchor task_wall@<wing> = centre of the surface.
   * The header is a light band (`light: true`, default): froglights alternating with walnut, lighting the board.
   */
  taskWall(x0, z0, x1, z1, facing, height, wing = 1, { y = this.feet, light = true } = {}) {
    const f = DIR[facing];
    const cells = cellsOf(x0, z0, x1, z1);
    const axis = z0 === z1 ? 'x' : 'z';
    cells.forEach(([x, z], i) => {
      this.set(x, y, z, B.walnutTrim);
      for (let h = 1; h <= height; h++) this.set(x, y + h, z, B.taskBoard, { facing }, { binding: `repo:#${wing}` });
      if (light && i % 2 === 0) this.set(x, y + height + 1, z, B.glowPanel, { axis });
      else this.set(x, y + height + 1, z, B.walnutTrim);
    });
    const alongX = z0 === z1;
    const [px, pz] = alongX ? [[Math.min(x0, x1) - 1, z0], [Math.max(x0, x1) + 1, z0]] : [[x0, Math.min(z0, z1) - 1], [x0, Math.max(z0, z1) + 1]];
    for (const [x, z] of [px, pz]) for (let h = 0; h <= height + 1; h++) this.set(x, y + h, z, B.walnut);
    const cx = cells.reduce((s, c) => s + c[0] + 0.5, 0) / cells.length;
    const cz = cells.reduce((s, c) => s + c[1] + 0.5, 0) / cells.length;
    const off = 0.125 + 0.002;
    const sx = f.dx !== 0 ? Math.floor(cx) + (f.dx > 0 ? off : 1 - off) : cx;
    const sz = f.dz !== 0 ? Math.floor(cz) + (f.dz > 0 ? off : 1 - off) : cz;
    this.anchor(`task_wall@${wing}`, sx, y + 1 + height / 2, sz, yawOf(facing));
    return this;
  }

  /** The decision podium (front faces `facing`); anchor decision_podium on its top. */
  podium(x, z, facing, { y = this.feet } = {}) {
    this.set(x, y, z, B.podium, { facing, open: 'false' });
    this.anchor('decision_podium', x + 0.5, y + 0.95, z + 0.5, yawOf(facing));
    return this;
  }

  /** A row of merge-station blocks on the segment (front faces `facing`) + `merge` lamp; stand spots in front (slots mergestation, _2..). */
  mergeStation(x0, z0, x1, z1, facing, { y = this.feet, lamp = true } = {}) {
    const f = DIR[facing];
    const cells = cellsOf(x0, z0, x1, z1);
    for (const [x, z] of cells) this.set(x, y, z, B.mergeStation, { facing, active: 'false' });
    const order = [...cells.keys()].sort((a, b) => Math.abs(a - (cells.length - 1) / 2) - Math.abs(b - (cells.length - 1) / 2));
    for (const i of order) this.spot('mergestation', cells[i][0] + f.dx, cells[i][1] + f.dz, yawOf(OPPOSITE[facing]), { y });
    if (lamp) {
      // in the wall behind the middle station block, backed on the outside (wallLamp)
      const [mx, mz] = cells[Math.floor(cells.length / 2)];
      this.wallLamp(mx - f.dx, y + 2, mz - f.dz, facing, 'merge', 'off');
    }
    return this;
  }

  /**
   * A console terminal block; `station` ('terminal' | 'testbench') gets a stand spot in front, facing it.
   * `wing` (group buildings): the spots are also written as per-wing anchors `<station>@<wing>`,
   * `<station>_2@<wing>`.. (placement renames them `<station>:<repoId>`), next to the shared slots.
   */
  console(x, z, facing, station = 'terminal', { y = this.feet, slots = 1, wing = null } = {}) {
    const f = DIR[facing];
    const lat = { dx: -f.dz, dz: f.dx };
    this.set(x, y, z, B.console, { facing });
    for (let i = 0; i < slots; i++) {
      const off = i === 0 ? 0 : (i % 2 ? 1 : -1) * Math.ceil(i / 2);
      const name = this.spot(station, x + f.dx + lat.dx * off, z + f.dz + lat.dz * off, yawOf(OPPOSITE[facing]), { y });
      if (wing != null) {
        const a = this.anchors[name];
        this.anchors[`${i === 0 ? station : `${station}_${i + 1}`}@${wing}`] = { ...a };
      }
    }
    return this;
  }

  /**
   * A trophy wall (docs/BUILDINGS.md "Trophy slots"): a lit, framed section of an inside wall where the mod hangs
   * vanilla wall signs. (x0,z0)-(x1,z1) = the WALL cells of the segment (one row of cells, `cols` long, default 3);
   * `facing` = the side the signs face (into the room). Sign cells are the room cells in front of the wall, `rows`
   * high from `y` (default feet+1) and written as explicit AIR; each has a full opaque `backing` block behind it
   * (the wall cell). A walnut frame surrounds it, a row of `glow` panels above lights it. Anchors `trophy@<w>`,
   * `trophy_2@<w>` .. (`trophy`, `trophy_2`.. without `wing`): centre of the sign cell, yaw = the sign's front.
   * Fill order = reading order seen from the room: top row first, left to right. The segment's wall cells and the
   * frame must not hold anything else (windows, doors, stations).
   */
  trophyWall(x0, z0, x1, z1, facing, { slots = 6, rows = Math.ceil(slots / 3), y = this.feet + 1, wing = null, backing = B.plaster, frame = B.walnut, glow = B.glowPanel } = {}) {
    const f = DIR[facing];
    const lat = { dx: -f.dz, dz: f.dx }; // viewer's left while looking at the wall
    const wall = cellsOf(x0, z0, x1, z1).sort((a, b) => (b[0] * lat.dx + b[1] * lat.dz) - (a[0] * lat.dx + a[1] * lat.dz)); // leftmost first
    const cols = wall.length;
    if (slots < 1 || slots > rows * cols) throw new Error(`${this.id}: trophyWall: ${slots} slots do not fit ${rows} x ${cols}`);
    const [ex0, ez0] = [wall[0][0] + lat.dx, wall[0][1] + lat.dz]; // frame ends, one cell beyond the segment
    const [ex1, ez1] = [wall[cols - 1][0] - lat.dx, wall[cols - 1][1] - lat.dz];
    const top = y + rows; // glow row
    for (const [x, z] of [[ex0, ez0], [ex1, ez1]]) this.fill([x, y - 1, z, x, top, z], frame);
    for (const [x, z] of wall) {
      for (let r = 0; r < rows; r++) this.set(x, y + r, z, backing);
      this.set(x, top, z, glow);
      this.set(x, y - 1, z, B.walnutTrim);
      for (let r = 0; r < rows; r++) {
        const old = this.nameAt(x + f.dx, y + r, z + f.dz);
        if (old && old !== B.air) throw new Error(`${this.id}: trophyWall: sign cell ${x + f.dx},${y + r},${z + f.dz} already holds ${old}`);
        this.air(x + f.dx, y + r, z + f.dz);
      }
    }
    const prefix = 'trophy';
    for (let i = 0; i < slots; i++) {
      const r = Math.floor(i / cols);
      const [wx, wz] = wall[i % cols];
      const name = `${i === 0 ? prefix : `${prefix}_${i + 1}`}${wing != null ? `@${wing}` : ''}`;
      this.anchor(name, wx + f.dx + 0.5, y + (rows - 1 - r) + 0.5, wz + f.dz + 0.5, yawOf(facing));
    }
    return this;
  }

  /**
   * Memory archive shelves on the segment (front faces `facing`), `height` high, one memory_catalog at the
   * last cell; spots in front (library, library_2..).
   */
  library(x0, z0, x1, z1, facing, { height = 3, y = this.feet, binding = 'shared' } = {}) {
    const f = DIR[facing];
    const cells = cellsOf(x0, z0, x1, z1);
    cells.forEach(([x, z], i) => {
      if (i === cells.length - 1 && cells.length > 1) {
        this.set(x, y, z, B.catalog, { facing });
        return;
      }
      for (let h = 0; h < height; h++) this.set(x, y + h, z, B.archive, { facing }, { binding });
    });
    for (let i = 0; i < Math.max(1, cells.length - 1); i += 1) this.spot('library', cells[i][0] + f.dx, cells[i][1] + f.dz, yawOf(OPPOSITE[facing]), { y });
    return this;
  }

  /** A chair/sofa seat for a sitter looking in `sitterYaw`; `station` ('lounge' | 'meeting') gets the next slot (the seat cell itself). */
  seat(x, z, sitterYaw, station, { y = this.feet, block = station === 'meeting' ? B.meetingStairs : B.sofaStairs } = {}) {
    const back = OPPOSITE[dirOfYaw(sitterYaw)];
    this.set(x, y, z, block, { facing: back, half: 'bottom', shape: 'straight' });
    if (station) this.spot(station, x, z, sitterYaw, { y });
    return this;
  }

  loungeSeat(x, z, sitterYaw, opts) { return this.seat(x, z, sitterYaw, 'lounge', opts); }

  /** Meeting table: a slab top over the rectangle; `seats` = [[x,z,sitterYaw],...]. Adds meeting slots. */
  meeting(x0, z0, x1, z1, seats, { y = this.feet, rug = true } = {}) {
    if (rug) {
      const xs = seats.map((q) => q[0]).concat([x0, x1]);
      const zs = seats.map((q) => q[1]).concat([z0, z1]);
      this.rug(Math.min(...xs) - 1, Math.min(...zs) - 1, Math.max(...xs) + 1, Math.max(...zs) + 1, y, { fill: 'minecraft:light_gray_carpet' });
    }
    for (const [x, z] of cellsOf(x0, z0, x1, z1)) this.slab(x, y, z, 'top');
    for (const [x, z, yaw] of seats) this.seat(x, z, yaw, 'meeting', { y });
    return this;
  }

  /** Low coffee table (bottom slabs, nobody walks over it). */
  coffeeTable(x0, z0, x1, z1, { y = this.feet } = {}) {
    for (const [x, z] of cellsOf(x0, z0, x1, z1)) this.slab(x, y, z, 'bottom');
    return this;
  }

  // ------------------------------------------------------------------ output

  /** Connect glass panes to neighbouring panes / solid cubes (vanilla would on neighbour update). */
  finalize() {
    for (const [k, c] of this.cells) {
      if (c.state.name !== 'minecraft:glass_pane') continue;
      const [x, y, z] = k.split(',').map(Number);
      const props = { ...c.state.props };
      for (const d of Object.keys(DIR)) {
        const n = this.cells.get(key(x + DIR[d].dx, y, z + DIR[d].dz));
        props[d] = String(!!n && (n.state.name === 'minecraft:glass_pane' || isCube(n.state)));
      }
      c.state = { name: c.state.name, props };
    }
    return this;
  }

  /** Written blocks as { x, y, z, state:{name,props}, nbt } sorted by y, z, x. */
  entries() {
    this.finalize();
    return [...this.cells].map(([k, c]) => {
      const [x, y, z] = k.split(',').map(Number);
      return { x, y, z, state: c.state, nbt: c.nbt };
    }).sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
  }

  /** The vanilla structure template as a tagged NBT root compound. */
  toStructure() {
    const list = this.entries();
    const palette = [];
    const index = new Map();
    const blocks = [];
    for (const e of list) {
      const pk = qualify(e.state.name) + JSON.stringify(e.state.props);
      if (!index.has(pk)) {
        index.set(pk, palette.length);
        const entry = { id: nbt.str(e.state.name) };
        if (Object.keys(e.state.props).length) {
          entry.properties = nbt.compound(Object.fromEntries(Object.entries(e.state.props).map(([k, v]) => [k, nbt.str(v)])));
        }
        palette.push(nbt.compound(entry));
      }
      const b = { pos: nbt.list('int', [nbt.int(e.x), nbt.int(e.y), nbt.int(e.z)]), state: nbt.int(index.get(pk)) };
      if (e.nbt) b.nbt = nbt.compound(Object.fromEntries(Object.entries(e.nbt).map(([k, v]) => [k, typeof v === 'number' ? nbt.int(v) : nbt.str(v)])));
      blocks.push(nbt.compound(b));
    }
    return nbt.compound({
      DataVersion: nbt.int(DATA_VERSION),
      size: nbt.list('int', [nbt.int(this.size.x), nbt.int(this.size.y), nbt.int(this.size.z)]),
      palette: nbt.list('compound', palette),
      blocks: nbt.list('compound', blocks),
      entities: nbt.list('compound', []),
    });
  }

  /** The `<id>.blueprint.json` sidecar object. */
  sidecar() {
    if (!this.walk) throw new Error(`${this.id}: walk region not set (setWalk)`);
    return {
      id: this.id,
      name: this.name,
      description: this.description,
      kind: this.kind,
      wings: this.wings,
      size: { ...this.size },
      groundY: this.groundY,
      front: this.front,
      materials: this.materials,
      foundationBlock: this.foundationBlock,
      approach: { ...this.approach },
      walk: { ...this.walk },
      anchors: Object.fromEntries(Object.entries(this.anchors).map(([k, v]) => [k, { ...v }])),
    };
  }
}
