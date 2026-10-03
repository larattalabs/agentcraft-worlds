// Minimal NBT reader/writer (big-endian, gzip) for structure templates. No dependencies.
// Writing uses tagged values: int(1), str('x'), list('compound', [...]), compound({...}).
// Reading returns plain JS: compounds -> object, lists -> array, longs -> BigInt, the rest numbers/strings.
import zlib from 'node:zlib';

export const T = { end: 0, byte: 1, short: 2, int: 3, long: 4, float: 5, double: 6, byteArray: 7, string: 8, list: 9, compound: 10, intArray: 11, longArray: 12 };

export const tag = (t, v) => ({ __nbt: t, v });
export const int = (v) => tag('int', v);
export const byte = (v) => tag('byte', v);
export const str = (v) => tag('string', v);
/** items: tagged values (or raw values for non-compound item types) */
export const list = (itemType, items) => tag('list', { itemType, items });
export const compound = (o) => tag('compound', o);

function writeValue(b, type, v) {
  switch (type) {
    case 'byte': b.push(Buffer.from([v & 0xff])); break;
    case 'short': { const x = Buffer.alloc(2); x.writeInt16BE(v); b.push(x); break; }
    case 'int': { const x = Buffer.alloc(4); x.writeInt32BE(v); b.push(x); break; }
    case 'long': { const x = Buffer.alloc(8); x.writeBigInt64BE(BigInt(v)); b.push(x); break; }
    case 'float': { const x = Buffer.alloc(4); x.writeFloatBE(v); b.push(x); break; }
    case 'double': { const x = Buffer.alloc(8); x.writeDoubleBE(v); b.push(x); break; }
    case 'string': { const s = Buffer.from(v, 'utf8'); const x = Buffer.alloc(2); x.writeUInt16BE(s.length); b.push(x, s); break; }
    case 'list': {
      b.push(Buffer.from([v.items.length ? T[v.itemType] : 0]));
      const n = Buffer.alloc(4); n.writeInt32BE(v.items.length); b.push(n);
      for (const it of v.items) writeValue(b, v.itemType, it && it.__nbt ? it.v : it);
      break;
    }
    case 'compound':
      for (const [k, val] of Object.entries(v)) {
        b.push(Buffer.from([T[val.__nbt]]));
        writeValue(b, 'string', k);
        writeValue(b, val.__nbt, val.v);
      }
      b.push(Buffer.from([0]));
      break;
    default: throw new Error(`cannot write NBT type ${type}`);
  }
}

/** Serialize a root compound ({key: tagged value}) to gzipped NBT. */
export function writeNbt(rootCompound, rootName = '') {
  const b = [Buffer.from([T.compound])];
  writeValue(b, 'string', rootName);
  writeValue(b, 'compound', rootCompound);
  return zlib.gzipSync(Buffer.concat(b));
}

export function readNbt(input) {
  let buf = input;
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf);
  let o = 0;
  const read = (type) => {
    switch (type) {
      case 1: return buf.readInt8(o++);
      case 2: { const v = buf.readInt16BE(o); o += 2; return v; }
      case 3: { const v = buf.readInt32BE(o); o += 4; return v; }
      case 4: { const v = buf.readBigInt64BE(o); o += 8; return v; }
      case 5: { const v = buf.readFloatBE(o); o += 4; return v; }
      case 6: { const v = buf.readDoubleBE(o); o += 8; return v; }
      case 7: { const n = buf.readInt32BE(o); o += 4; const v = [...buf.subarray(o, o + n)]; o += n; return v; }
      case 8: { const n = buf.readUInt16BE(o); o += 2; const v = buf.toString('utf8', o, o + n); o += n; return v; }
      case 9: { const it = buf[o++]; const n = buf.readInt32BE(o); o += 4; const a = []; for (let i = 0; i < n; i++) a.push(read(it)); return a; }
      case 10: {
        const c = {};
        for (;;) { const t = buf[o++]; if (t === 0) break; const k = read(8); c[k] = read(t); }
        return c;
      }
      case 11: { const n = buf.readInt32BE(o); o += 4; const a = []; for (let i = 0; i < n; i++) a.push(read(3)); return a; }
      case 12: { const n = buf.readInt32BE(o); o += 4; const a = []; for (let i = 0; i < n; i++) a.push(read(4)); return a; }
      default: throw new Error(`bad NBT tag ${type} at ${o}`);
    }
  };
  const t = buf[o++];
  if (t !== 10) throw new Error('NBT root is not a compound');
  read(8); // root name
  return read(10);
}
