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

/** Block ids (shorthand for designs). */
export const B = {
  air: 'minecraft:air',
  plaster: 'agentcraft:plaster_panel',
  plasterFrame: 'agentcraft:plaster_frame',
  walnut: 'agentcraft:walnut_panel',
  walnutTrim: 'agentcraft:walnut_trim',
  tile: 'agentcraft:terracotta_tile',
  parquet: 'agentcraft:oak_parquet',
  glowPanel: 'agentcraft:glow_panel',
  glowStrip: 'agentcraft:glow_strip',
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
  door: 'minecraft:dark_oak_door',
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
   *   walk?:number[]|object}} o
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
    this.cells = new Map(); // "x,y,z" -> { state:{name,props}, nbt }
    this.anchors = {};
    this.walk = null;
    if (o.walk) this.setWalk(o.walk);
  }

  /** Feet row (rows >= this are the building above the terrain). */
  get feet() { return this.groundY; }

  setWalk(w) {
    this.walk = Array.isArray(w)
      ? { minX: w[0], minY: w[1], minZ: w[2], maxX: w[3], maxY: w[4], maxZ: w[5] }
      : { minX: w.minX, minY: w.minY, minZ: w.minZ, maxX: w.maxX, maxY: w.maxY, maxZ: w.maxZ };
    return this;
  }

  // ------------------------------------------------------------------ cells

  inBounds(x, y, z) {
    return x >= 0 && y >= 0 && z >= 0 && x < this.size.x && y < this.size.y && z < this.size.z;
  }

  /** Place one block. `props` may be partial (the rest is filled with defaults); `nbt` is a block-entity compound as a plain object. */
  set(x, y, z, block, props = {}, nbtData = null) {
    if (!this.inBounds(x, y, z)) throw new Error(`${this.id}: set(${x},${y},${z}) outside size ${this.size.x}x${this.size.y}x${this.size.z}`);
    this.cells.set(key(x, y, z), { state: normalize(block, props), nbt: nbtData });
    return this;
  }

  air(x, y, z) { return this.set(x, y, z, B.air); }

  get(x, y, z) { return this.cells.get(key(x, y, z)) ?? null; }

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

  /** A two-high door in the wall cell (x,y,z); the cell above it is the door's upper half. */
  door(x, y, z, facing, { hinge = 'left', open = false, block = B.door } = {}) {
    this.set(x, y, z, block, { facing, half: 'lower', hinge, open: String(open) });
    this.set(x, y + 1, z, block, { facing, half: 'upper', hinge, open: String(open) });
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
   * A gabled (sloped) roof: stairs rise from both long eaves to the ridge, full blocks at the ridge,
   * gable ends closed with `gable` below the slope. ridge: 'x' = ridge line runs east-west.
   * Rises one row per cell, so it needs (width/2) rows above `y`.
   */
  roofGable(x0, z0, x1, z1, y, { ridge = 'x', stairs = 'minecraft:dark_oak_stairs', full = 'minecraft:dark_oak_planks', gable = B.plaster } = {}) {
    const alongX = ridge === 'x';
    const a0 = alongX ? z0 : x0;
    const a1 = alongX ? z1 : x1;
    const b0 = alongX ? x0 : z0;
    const b1 = alongX ? x1 : z1;
    const mid = (a0 + a1) / 2;
    for (let a = a0; a <= a1; a++) {
      const rise = Math.min(a - a0, a1 - a);
      const towardRidge = a <= mid ? (alongX ? 'south' : 'east') : alongX ? 'north' : 'west';
      const isRidge = Number.isInteger(mid) && a === mid;
      for (let b = b0; b <= b1; b++) {
        const [x, z] = alongX ? [b, a] : [a, b];
        if (isRidge) this.set(x, y + rise, z, full);
        else this.set(x, y + rise, z, stairs, { facing: towardRidge, half: 'bottom', shape: 'straight' });
        for (let yy = y; yy < y + rise; yy++) if (b === b0 || b === b1) this.set(x, yy, z, gable);
      }
    }
    return this;
  }

  // ------------------------------------------------------------------ lights, decor

  /** glow_panel set flush (e.g. in a ceiling row). */
  glowPanel(x, y, z) { return this.set(x, y, z, B.glowPanel); }

  /** glow_strip whose front faces `facing` (attached to the wall on the opposite side). */
  glowStrip(x, y, z, facing, axis) {
    const ax = axis ?? (facing === 'east' || facing === 'west' ? 'z' : 'x');
    return this.set(x, y, z, B.glowStrip, { facing, axis: ax });
  }

  /** Invisible light source block (no collision). */
  invisibleLight(x, y, z, level = 15) { return this.set(x, y, z, B.light, { level }); }

  plant(x, y, z) { return this.set(x, y, z, B.plant); }

  lantern(x, y, z, hanging = false) { return this.set(x, y, z, B.lantern, { hanging: String(hanging) }); }

  candle(x, y, z, n = 2, lit = true) { return this.set(x, y, z, B.candle, { candles: n, lit: String(lit) }); }

  /** A status lamp station block bound to `binding` ("ci:#1", "goal", "merge", "agent:kit"...). */
  statusLamp(x, y, z, binding, status = 'idle') {
    return this.set(x, y, z, B.statusLamp, { status }, { binding });
  }

  // ------------------------------------------------------------------ anchors

  /** Raw anchor (world-relative template coordinates; spots = feet position). */
  anchor(name, x, y, z, yaw = 0, pitch = 0) {
    this.anchors[name] = { x: r3(x), y: r3(y), z: r3(z), yaw: r3(yaw), pitch: r3(pitch) };
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
   * wall), `height` rows high above a walnut sill, with a walnut header and posts, each cell bound
   * `repo:#<wing>`. `facing` is the direction the board faces. Anchor task_wall@<wing> = centre of the surface.
   */
  taskWall(x0, z0, x1, z1, facing, height, wing = 1, { y = this.feet } = {}) {
    const f = DIR[facing];
    const cells = cellsOf(x0, z0, x1, z1);
    for (const [x, z] of cells) {
      this.set(x, y, z, B.walnutTrim);
      for (let h = 1; h <= height; h++) this.set(x, y + h, z, B.taskBoard, { facing }, { binding: `repo:#${wing}` });
      this.set(x, y + height + 1, z, B.walnut);
    }
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
      const [mx, mz] = cells[Math.floor(cells.length / 2)];
      this.statusLamp(mx - f.dx, y + 2, mz - f.dz, 'merge', 'off');
    }
    return this;
  }

  /** A console terminal block; `station` ('terminal' | 'testbench') gets a stand spot in front, facing it. */
  console(x, z, facing, station = 'terminal', { y = this.feet, slots = 1 } = {}) {
    const f = DIR[facing];
    const lat = { dx: -f.dz, dz: f.dx };
    this.set(x, y, z, B.console, { facing });
    for (let i = 0; i < slots; i++) {
      const off = i === 0 ? 0 : (i % 2 ? 1 : -1) * Math.ceil(i / 2);
      this.spot(station, x + f.dx + lat.dx * off, z + f.dz + lat.dz * off, yawOf(OPPOSITE[facing]), { y });
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
      walk: { ...this.walk },
      anchors: Object.fromEntries(Object.entries(this.anchors).map(([k, v]) => [k, { ...v }])),
    };
  }
}
