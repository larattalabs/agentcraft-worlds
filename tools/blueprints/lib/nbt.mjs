// Minimal big-endian NBT writer/reader (+ gzip helpers): just enough for vanilla structure templates.
// Values are tagged so ints/strings/lists are unambiguous: nbt.int(3), nbt.str('x'), nbt.list('int', [..]),
// nbt.compound({ a: nbt.int(1) }). `parse` returns the same tagged shape; `plain` strips the tags.
import zlib from 'node:zlib';

export const TAG = { end: 0, byte: 1, short: 2, int: 3, long: 4, float: 5, double: 6, byteArray: 7, string: 8, list: 9, compound: 10, intArray: 11, longArray: 12 };
const NAMES = Object.fromEntries(Object.entries(TAG).map(([k, v]) => [v, k]));

export const nbt = {
  byte: (v) => ({ t: 'byte', v }),
  short: (v) => ({ t: 'short', v }),
  int: (v) => ({ t: 'int', v }),
  long: (v) => ({ t: 'long', v: BigInt(v) }),
  float: (v) => ({ t: 'float', v }),
  double: (v) => ({ t: 'double', v }),
  str: (v) => ({ t: 'string', v: String(v) }),
  intArray: (v) => ({ t: 'intArray', v }),
  /** items are tagged values of type `of` (use an empty array for an empty list). */
  list: (of, items = []) => ({ t: 'list', of, v: items }),
  compound: (obj = {}) => ({ t: 'compound', v: obj }),
};

class Out {
  constructor() { this.parts = []; }
  u8(n) { const b = Buffer.alloc(1); b.writeUInt8(n); this.parts.push(b); }
  i16(n) { const b = Buffer.alloc(2); b.writeInt16BE(n); this.parts.push(b); }
  u16(n) { const b = Buffer.alloc(2); b.writeUInt16BE(n); this.parts.push(b); }
  i32(n) { const b = Buffer.alloc(4); b.writeInt32BE(n); this.parts.push(b); }
  i64(n) { const b = Buffer.alloc(8); b.writeBigInt64BE(n); this.parts.push(b); }
  f32(n) { const b = Buffer.alloc(4); b.writeFloatBE(n); this.parts.push(b); }
  f64(n) { const b = Buffer.alloc(8); b.writeDoubleBE(n); this.parts.push(b); }
  str(s) { const b = Buffer.from(s, 'utf8'); this.u16(b.length); this.parts.push(b); }
  buf() { return Buffer.concat(this.parts); }
}

function writePayload(o, tag) {
  switch (tag.t) {
    case 'byte': o.u8(tag.v & 0xff); break;
    case 'short': o.i16(tag.v); break;
    case 'int': o.i32(tag.v); break;
    case 'long': o.i64(tag.v); break;
    case 'float': o.f32(tag.v); break;
    case 'double': o.f64(tag.v); break;
    case 'string': o.str(tag.v); break;
    case 'intArray': o.i32(tag.v.length); tag.v.forEach((n) => o.i32(n)); break;
    case 'list':
      o.u8(tag.v.length === 0 ? TAG.end : TAG[tag.of]);
      o.i32(tag.v.length);
      for (const item of tag.v) {
        if (item.t !== tag.of) throw new Error(`list of ${tag.of} contains a ${item.t}`);
        writePayload(o, item);
      }
      break;
    case 'compound':
      for (const [k, v] of Object.entries(tag.v)) {
        o.u8(TAG[v.t]);
        o.str(k);
        writePayload(o, v);
      }
      o.u8(TAG.end);
      break;
    default: throw new Error(`unsupported tag ${tag.t}`);
  }
}

/** Serialise a root compound (named "") to raw (uncompressed) NBT. */
export function encode(root) {
  if (root.t !== 'compound') throw new Error('root must be a compound');
  const o = new Out();
  o.u8(TAG.compound);
  o.str('');
  writePayload(o, root);
  return o.buf();
}

export function encodeGzip(root) {
  return zlib.gzipSync(encode(root), { level: 9 });
}

function readPayload(b, pos, type) {
  switch (type) {
    case TAG.byte: return [nbt.byte(b.readInt8(pos)), pos + 1];
    case TAG.short: return [nbt.short(b.readInt16BE(pos)), pos + 2];
    case TAG.int: return [nbt.int(b.readInt32BE(pos)), pos + 4];
    case TAG.long: return [nbt.long(b.readBigInt64BE(pos)), pos + 8];
    case TAG.float: return [nbt.float(b.readFloatBE(pos)), pos + 4];
    case TAG.double: return [nbt.double(b.readDoubleBE(pos)), pos + 8];
    case TAG.string: { const n = b.readUInt16BE(pos); return [nbt.str(b.toString('utf8', pos + 2, pos + 2 + n)), pos + 2 + n]; }
    case TAG.intArray: {
      const n = b.readInt32BE(pos); const v = [];
      for (let i = 0; i < n; i++) v.push(b.readInt32BE(pos + 4 + 4 * i));
      return [nbt.intArray(v), pos + 4 + 4 * n];
    }
    case TAG.list: {
      const et = b.readUInt8(pos); const n = b.readInt32BE(pos + 1); let p = pos + 5; const v = [];
      for (let i = 0; i < n; i++) { const [t, np] = readPayload(b, p, et); v.push(t); p = np; }
      return [nbt.list(NAMES[et], v), p];
    }
    case TAG.compound: {
      const v = {}; let p = pos;
      for (;;) {
        const t = b.readUInt8(p++);
        if (t === TAG.end) break;
        const n = b.readUInt16BE(p); const key = b.toString('utf8', p + 2, p + 2 + n); p += 2 + n;
        const [val, np] = readPayload(b, p, t); v[key] = val; p = np;
      }
      return [nbt.compound(v), p];
    }
    default: throw new Error(`unsupported tag id ${type}`);
  }
}

/** Parse raw NBT bytes (root compound) into tagged values. */
export function decode(buf) {
  if (buf.readUInt8(0) !== TAG.compound) throw new Error('root tag is not a compound');
  const n = buf.readUInt16BE(1);
  const [root, end] = readPayload(buf, 3 + n, TAG.compound);
  if (end !== buf.length) throw new Error(`trailing bytes after root compound (${buf.length - end})`);
  return root;
}

/** Parse a (gzipped or raw) NBT file's bytes. */
export function parse(buf) {
  const raw = buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b ? zlib.gunzipSync(buf) : buf;
  return decode(raw);
}

/** Strip tags: compounds -> objects, lists -> arrays, scalars -> values (longs stay bigint). */
export function plain(tag) {
  if (tag.t === 'compound') return Object.fromEntries(Object.entries(tag.v).map(([k, v]) => [k, plain(v)]));
  if (tag.t === 'list') return tag.v.map(plain);
  return tag.v;
}
