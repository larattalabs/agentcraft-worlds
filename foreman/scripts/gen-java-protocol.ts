// Generates the mod's Java mirror of the protocol from src/protocol.ts (the same zod schemas
// docs/protocol.md is generated from), plus the JSON fixtures the mod's round-trip test reads
// (src/protocol-examples.ts). Run: npm run gen:java-protocol   (CI-style check: --check)
//
// Output:
//   mod/src/client/java/dev/agentcraft/client/foreman/Protocol.java   records + enums (GENERATED)
//   mod/src/test/resources/protocol-examples.json                     the protocol examples
//
// Mapping (docs: mod/DEV.md "Protocol mirror"):
//   - named enums (ENUM_SCHEMAS) -> Java enums implementing Protocol.Wire, with an UNKNOWN constant
//     (unknown wire values from a newer Foreman decode to it); inline string unions -> String unless
//     JAVA.inlineNames gives them a name
//   - entities and other exported object schemas -> records named like the export; inline objects
//     -> records named by JAVA.inlineNames (an unnamed one is an error)
//   - server messages -> records named like the export without "Msg" (Snapshot, AgentUpsert, ...),
//     unless that clashes (ForemanStatusMsg, ErrorMsg); client messages keep the export name
//     (HelloMsg, GoalSubmitMsg, ...). The envelope (v, type, id) is not part of the records.
//   - optional -> @Nullable and boxed; timestamps (Ts) -> long; other integers -> int; numbers ->
//     double; unknown / unions -> JsonElement; record<string, unknown> -> JsonObject
//   - required enums default to UNKNOWN and required lists / maps to empty when a field is missing
//     (an older Foreman); every list / map is copied (immutable)
//   - hand-written behaviour lives in ProtocolSupport.java: JAVA.mixins makes a record or enum
//     implement one of its interfaces (default methods over the record's accessors)
//   - JAVA.fields: per-field exceptions kept from the hand-written mirror (nullable although
//     required, String instead of an enum, a default for a missing value, a Java name)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { z } from 'zod';
import * as P from '../src/protocol.js';
import { CLIENT_EXAMPLES, SERVER_EXAMPLES } from '../src/protocol-examples.js';

type AnySchema = z.ZodType;
interface Def {
  type: string;
  shape?: Record<string, AnySchema>;
  innerType?: AnySchema;
  element?: AnySchema;
  options?: AnySchema[];
  entries?: Record<string, string>;
  values?: unknown[];
  keyType?: AnySchema;
  valueType?: AnySchema;
  checks?: unknown[];
}

const def = (s: AnySchema): Def => (s as unknown as { _zod: { def: Def } })._zod.def;
const descOf = (s: AnySchema): string | undefined => (s as unknown as { description?: string }).description;

// ---------------------------------------------------------------------------------------------
// Java-specific choices. Every key is checked against the schemas: a stale one fails the run.
// ---------------------------------------------------------------------------------------------

interface FieldRule {
  /** @Nullable (boxed) although the schema requires it: older Foremen did not send it, or null means something. */
  nullable?: true;
  /** Keep a named / inline enum as its wire string (display only, or echoed back exactly as sent). */
  asString?: true;
  /** Java expression used when the value is missing (null), e.g. `""`, `id`. */
  default?: string;
  /** Java expression the field is always replaced with (may read the other components). */
  init?: string;
  /** Java component name when the wire name is not usable (keywords). */
  javaName?: string;
}

const JAVA = {
  /** Inline objects / string unions -> Java type name (`Owner.field`; arrays name their element). */
  inlineNames: {
    'TaskPr.threads': 'PrThreads',
    'RepoSettingsView.pr': 'RepoPrSettings',
    'RepoSettingsView.prReview': 'RepoPrReview',
    'DesignRequest.maxSize': 'Size3',
    'Design.size': 'Size3',
    'DiffLine.kind': 'DiffLineKind',
    'DiffFile.status': 'DiffFileStatus',
    'Diff.stats': 'DiffStats',
    'LeadSyncMsg.buildings': 'LeadSyncBuilding',
    'ConfigSetMsg.changes': 'ConfigChange',
  } as Record<string, string>,
  /** Record / enum -> ProtocolSupport interface it implements (hand-written behaviour). */
  mixins: {
    AgentState: 'AgentStateHelpers',
    DesignStatus: 'DesignStatusHelpers',
    Agent: 'AgentHelpers',
    TaskPr: 'TaskPrHelpers',
    Decision: 'DecisionHelpers',
    Goal: 'GoalHelpers',
  } as Record<string, string>,
  fields: {
    'Agent.name': { init: 'ProtocolSupport.displayName(id, name)' },
    'Agent.color': { default: '"#9C9488"' },
    'Agent.skin': { default: 'id' },
    'Agent.activity': { default: '""' },
    'Agent.paused': { nullable: true },
    'Agent.active': { nullable: true },
    'LogEntry.text': { default: '""' },
    'TaskPr.url': { default: '""' },
    'TaskPr.host': { nullable: true },
    'TaskPr.branch': { nullable: true },
    'TaskPr.target': { nullable: true },
    'TaskPr.status': { asString: true, default: '"open"' },
    'TaskPr.checks': { asString: true, nullable: true },
    'TaskPr.threads': { nullable: true },
    'PrThreads.new': { javaName: 'newCount', nullable: true },
    'Task.title': { default: 'id' },
    'Task.createdBy': { nullable: true },
    'Decision.question': { default: '""' },
    'RepoSettingsView.land': { default: '"merge"' },
    'RepoSettingsView.envKeys': { default: 'List.of()' },
    'RepoPrReview.maxRounds': { nullable: true },
    'Repo.name': { default: 'id' },
    'MemoryEntry.scope': { default: '"shared"' },
    'MemoryEntry.title': { default: 'id' },
    'MemoryEntry.body': { default: '""' },
    'GoalPr.taskId': { nullable: true },
    'GoalPr.url': { nullable: true },
    'GoalPr.status': { asString: true, nullable: true },
    'Goal.text': { default: '""' },
    'FeedItem.text': { default: '""' },
    'UsageWindow.id': { default: '""' },
    'UsageWindow.label': { default: 'id' },
    'ForemanHold.reason': { default: '"unknown"' },
    'ForemanHold.message': { default: '""' },
    'ForemanStatus.version': { default: '"?"' },
    'DiffLine.text': { default: '""' },
    'DiffHunk.header': { default: '""' },
    'Diff.stats': { default: 'new DiffStats(0, 0, 0)' },
    'DesignRequest.kind': { default: '"single"' },
    'DesignRequest.style': { asString: true, default: '""' },
    'DesignRequest.materials': { default: '"agentcraft"' },
    'DesignRequest.features': { asString: true },
    'DesignRequest.maxSize': { default: 'new Size3(0, 0, 0)' },
    'DesignRequest.outDir': { default: '""' },
    'Design.step': { default: '""' },
    'Design.previews': { default: 'List.of()' },
    'DigestLine.kind': { asString: true, default: '"message"' },
    'DigestLine.text': { default: '""' },
    'GoalDigest.text': { nullable: true },
    'GoalDigest.status': { nullable: true },
    'Snapshot.leads': { nullable: true },
    'AgentSay.text': { default: '""' },
    'Notify.text': { default: '""' },
    'SettingDef.default': { javaName: 'defaultValue' },
  } as Record<string, FieldRule>,
  /** Java names for the exact option labels (default: the label in UPPER_SNAKE). */
  optionNames: { 'Always allow for this agent': 'ALWAYS_ALLOW' } as Record<string, string>,
};

const JAVA_KEYWORDS = new Set(
  'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null record var yield'.split(' '),
);
/** Server message names that would clash with java.lang or an entity keep their "Msg". */
const RESERVED_TYPE_NAMES = new Set(['Error', 'Object', 'Record', 'String', 'Class', 'Enum', 'Override']);
const ENVELOPE = new Set(['v', 'type', 'id']);

// ---------------------------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------------------------

interface JField {
  wire: string;
  name: string;
  /** declared Java type, e.g. `@Nullable String`, `List<Agent>`, `int` */
  type: string;
  desc?: string;
  /** compact-constructor statement, if any */
  norm?: string;
}
interface JRecord {
  kind: 'record';
  name: string;
  doc?: string;
  fields: JField[];
  /** structural signature, to check that two inline objects sharing a name are the same */
  sig: string;
}
interface JEnum {
  kind: 'enum';
  name: string;
  doc?: string;
  values: string[];
}

const usedRules = new Set<string>();
const usedInline = new Set<string>();
const types = new Map<string, JRecord | JEnum>();
const order: string[] = [];
const named = new Map<AnySchema, string>();

// every exported schema gets its export name; entities and enums take precedence
const exportName = new Map<AnySchema, string>();
for (const [k, v] of Object.entries(P)) {
  if (v && typeof v === 'object' && '_zod' in (v as object)) exportName.set(v as AnySchema, k);
}
for (const [k, v] of Object.entries(P.ENUM_SCHEMAS)) named.set(v as AnySchema, k);
for (const [k, v] of Object.entries(P.ENTITY_SCHEMAS)) named.set(v as AnySchema, k);

const tsChecks = def(P.Ts).checks ?? [];
const isTs = (d: Def) => tsChecks.length > 0 && tsChecks.every((c) => (d.checks ?? []).includes(c));
const isInt = (d: Def) =>
  (d.checks ?? []).some((c) => {
    const f = (c as { _zod?: { def?: { format?: string; check?: string } } })._zod?.def;
    return f?.format === 'safeint' || f?.format === 'int32';
  });

const constName = (wire: string) =>
  wire
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .toUpperCase();

function rule(owner: string, field: string): FieldRule {
  const key = `${owner}.${field}`;
  const r = JAVA.fields[key];
  if (r) usedRules.add(key);
  return r ?? {};
}

function inlineName(owner: string, field: string): string | undefined {
  const key = `${owner}.${field}`;
  const n = JAVA.inlineNames[key];
  if (n) usedInline.add(key);
  return n;
}

function addType(t: JRecord | JEnum) {
  const prev = types.get(t.name);
  if (prev) {
    if (prev.kind !== t.kind || (prev.kind === 'record' && t.kind === 'record' && prev.sig !== t.sig) || (prev.kind === 'enum' && t.kind === 'enum' && prev.values.join() !== t.values.join())) {
      throw new Error(`two different types are both named ${t.name}`);
    }
    return;
  }
  types.set(t.name, t);
  order.push(t.name);
}

function enumType(name: string, s: AnySchema) {
  addType({ kind: 'enum', name, values: Object.values(def(s).entries ?? {}), ...(descOf(s) ? { doc: descOf(s) } : {}) });
}

const box = (t: string) => ({ int: 'Integer', long: 'Long', double: 'Double', boolean: 'Boolean' })[t] ?? t;

/** Java type of a (non-optional) schema. `owner.field` names inline types and finds field rules. */
/** `.describe()` returns a copy, so `CiStatus.describe(...)` is found by its values instead of identity. */
function namedEnum(d: Def): string | undefined {
  const vals = Object.values(d.entries ?? {}).join('|');
  for (const [k, v] of Object.entries(P.ENUM_SCHEMAS)) if (Object.values(def(v as AnySchema).entries ?? {}).join('|') === vals) return k;
  return undefined;
}

function javaType(s: AnySchema, owner: string, field: string, r: FieldRule): string {
  const d = def(s);
  const n = named.get(s) ?? (d.type === 'enum' ? namedEnum(d) : undefined);
  if (n && d.type === 'enum') {
    if (r.asString) return 'String';
    enumType(n, s);
    return n;
  }
  if (n && d.type === 'object') {
    recordType(n, s);
    return n;
  }
  switch (d.type) {
    case 'string':
      return 'String';
    case 'boolean':
      return 'boolean';
    case 'number':
      return isInt(d) ? (isTs(d) ? 'long' : 'int') : 'double';
    case 'literal': {
      const vals = d.values ?? [];
      if (vals.every((v) => typeof v === 'string')) return 'String';
      if (vals.every((v) => Number.isInteger(v))) return 'int';
      throw new Error(`${owner}.${field}: unsupported literal ${JSON.stringify(vals)}`);
    }
    case 'unknown':
    case 'union':
      return 'JsonElement';
    case 'enum': {
      const iname = inlineName(owner, field);
      if (!iname || r.asString) return 'String';
      enumType(iname, s);
      return iname;
    }
    case 'array':
      return `List<${box(javaType(d.element!, owner, field, r))}>`;
    case 'record': {
      if (def(d.keyType!).type !== 'string') throw new Error(`${owner}.${field}: only string-keyed records are supported`);
      const vt = def(d.valueType!).type;
      if (vt === 'unknown') return 'JsonObject';
      return `Map<String, ${box(javaType(d.valueType!, owner, field, r))}>`;
    }
    case 'object': {
      // a described copy of a named object (`Goal.describe(...)`) has no identity match: refuse rather than
      // silently emit a second record for the same shape
      const iname = inlineName(owner, field) ?? exportName.get(s);
      if (!iname) throw new Error(`${owner}.${field}: inline object without a Java name: export it or add it to JAVA.inlineNames`);
      recordType(iname, s);
      return iname;
    }
    default:
      throw new Error(`${owner}.${field}: unsupported zod type "${d.type}" (teach scripts/gen-java-protocol.ts)`);
  }
}

function recordType(name: string, s: AnySchema, doc?: string, skipEnvelope = false): void {
  // an entity is walked once; inline objects are walked again so addType can check that same-named ones match
  if (types.has(name) && types.get(name)!.kind === 'record' && named.get(s) === name) return;
  const shape = def(s).shape ?? {};
  const fields: JField[] = [];
  for (const [wire, fs_] of Object.entries(shape)) {
    if (skipEnvelope && ENVELOPE.has(wire)) continue;
    let cur = fs_;
    let optional = false;
    let desc = descOf(fs_);
    while (def(cur).type === 'optional' || def(cur).type === 'default') {
      optional = true;
      cur = def(cur).innerType!;
      desc ??= descOf(cur);
    }
    const r = rule(name, wire);
    const t = javaType(cur, name, wire, r);
    const jname = r.javaName ?? wire;
    if (JAVA_KEYWORDS.has(jname)) throw new Error(`${name}.${wire} is a Java keyword: give it a javaName`);
    // a defaulted field is never null after construction
    const nullable = (optional || r.nullable === true) && !r.default && !r.init;
    const isList = t.startsWith('List<');
    const isMap = t.startsWith('Map<');
    const enumT = types.get(t)?.kind === 'enum';
    let declared = nullable ? `@Nullable ${box(t)}` : t;
    let norm: string | undefined;
    const copy = isList ? `List.copyOf(${jname})` : isMap ? `Map.copyOf(${jname})` : undefined;
    if (r.init) {
      norm = `${jname} = ${r.init};`;
    } else if (r.default) {
      norm = `${jname} = ${jname} == null ? ${r.default} : ${copy ?? jname};`;
    } else if (!nullable && (isList || isMap)) {
      norm = `${jname} = ${jname} == null ? ${isList ? 'List.of()' : 'Map.of()'} : ${copy};`;
    } else if (nullable && (isList || isMap)) {
      norm = `${jname} = ${jname} == null ? null : ${copy};`;
    } else if (!nullable && enumT) {
      norm = `${jname} = ${jname} == null ? ${t}.UNKNOWN : ${jname};`;
    }
    if ((r.default || r.init) && ['int', 'long', 'double', 'boolean'].includes(t)) throw new Error(`${name}.${wire}: primitives cannot take a default`);
    fields.push({ wire, name: jname, type: declared, ...(desc ? { desc } : {}), ...(norm ? { norm } : {}) });
  }
  const sig = fields.map((f) => `${f.wire}:${f.name}:${f.type}`).join(',');
  addType({ kind: 'record', name, fields, sig, ...(doc ? { doc } : {}) });
}

// entities first (documentation order), then server and client messages
for (const [name, s] of Object.entries(P.ENUM_SCHEMAS)) enumType(name, s as AnySchema);
for (const [name, s] of Object.entries(P.ENTITY_SCHEMAS)) recordType(name, s as AnySchema);
const serverNames: [string, string][] = [];
for (const [type, { schema, doc }] of Object.entries(P.SERVER_MESSAGES)) {
  const ex = exportName.get(schema as AnySchema);
  if (!ex) throw new Error(`server message ${type} is not exported`);
  const short = ex.replace(/Msg$/, '');
  const name = types.has(short) || RESERVED_TYPE_NAMES.has(short) ? ex : short;
  recordType(name, schema as AnySchema, `\`${type}\`: ${doc}`, true);
  serverNames.push([type, name]);
}
const clientNames: [string, string][] = [];
for (const [type, { schema, doc }] of Object.entries(P.CLIENT_MESSAGES)) {
  const name = exportName.get(schema as AnySchema);
  if (!name) throw new Error(`client message ${type} is not exported`);
  recordType(name, schema as AnySchema, `\`${type}\`: ${doc}`, true);
  clientNames.push([type, name]);
}

const staleRules = Object.keys(JAVA.fields).filter((k) => !usedRules.has(k));
const staleInline = Object.keys(JAVA.inlineNames).filter((k) => !usedInline.has(k));
const staleMixins = Object.keys(JAVA.mixins).filter((k) => !types.has(k));
if (staleRules.length || staleInline.length || staleMixins.length) {
  throw new Error(`stale entries in JAVA (no such type/field in src/protocol.ts): ${[...staleRules, ...staleInline, ...staleMixins].join(', ')}`);
}

// ---------------------------------------------------------------------------------------------
// Java source
// ---------------------------------------------------------------------------------------------

const T = '\t';
const jdocText = (s: string) =>
  s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/@/g, '&#64;')
    .replace(/\*\//g, '*&#47;');

function javadoc(indent: string, lines: string[]): string[] {
  if (!lines.length) return [];
  if (lines.length === 1 && lines[0]!.length < 100) return [`${indent}/** ${lines[0]} */`];
  return [`${indent}/**`, ...lines.map((l) => (l ? `${indent} * ${l}` : `${indent} *`)), `${indent} */`];
}

function wrap(text: string, width = 110): string[] {
  const out: string[] = [];
  for (const para of text.split('\n')) {
    let line = '';
    for (const word of para.split(/\s+/)) {
      if (!word) continue;
      if (line && line.length + 1 + word.length > width) {
        out.push(line);
        line = word;
      } else {
        line = line ? `${line} ${word}` : word;
      }
    }
    out.push(line);
  }
  return out;
}

/** `items` joined by ", " over lines of at most ~130 columns, each starting with `indent`. */
function commaLines(indent: string, items: string[], end: string): string[] {
  const out: string[] = [];
  let line = '';
  items.forEach((it, i) => {
    const piece = it + (i < items.length - 1 ? ',' : end);
    if (line && indent.length * 4 + line.length + 1 + piece.length > 130) {
      out.push(indent + line);
      line = piece;
    } else {
      line = line ? `${line} ${piece}` : piece;
    }
  });
  out.push(indent + line);
  return out;
}

function emitEnum(e: JEnum): string[] {
  const out: string[] = [];
  out.push(...javadoc(T, e.doc ? wrap(jdocText(e.doc)) : []));
  const mixin = JAVA.mixins[e.name];
  const impl = mixin ? `Wire, ProtocolSupport.${mixin}` : 'Wire';
  const consts = e.values.map((w) => ({ c: constName(w), w }));
  const plain = consts.every(({ c, w }) => c.toLowerCase() === w);
  if (!consts.some(({ c }) => c === 'UNKNOWN')) consts.push({ c: 'UNKNOWN', w: 'unknown' });
  out.push(`${T}public enum ${e.name} implements ${impl} {`);
  if (plain) {
    out.push(...commaLines(`${T}${T}`, consts.map(({ c }) => c), ''));
  } else {
    out.push(...commaLines(`${T}${T}`, consts.map(({ c, w }) => `${c}(${JSON.stringify(w)})`), ';'));
    out.push('');
    out.push(`${T}${T}private final String wire;`);
    out.push('');
    out.push(`${T}${T}${e.name}(String wire) {`);
    out.push(`${T}${T}${T}this.wire = wire;`);
    out.push(`${T}${T}}`);
    out.push('');
    out.push(`${T}${T}@Override`);
    out.push(`${T}${T}public String wire() {`);
    out.push(`${T}${T}${T}return wire;`);
    out.push(`${T}${T}}`);
  }
  out.push(`${T}}`);
  return out;
}

function emitRecord(r: JRecord): string[] {
  const out: string[] = [];
  const doc: string[] = [];
  if (r.doc) doc.push(...wrap(jdocText(r.doc)));
  const described = r.fields.filter((f) => f.desc);
  if (described.length) {
    if (doc.length) doc.push('');
    doc.push('<ul>');
    for (const f of described) doc.push(...wrap(`<li>{@code ${f.wire}}: ${jdocText(f.desc!)}</li>`));
    doc.push('</ul>');
  }
  out.push(...javadoc(T, doc));
  const comps = r.fields.map((f) => (f.name !== f.wire ? `@SerializedName(${JSON.stringify(f.wire)}) ` : '') + `${f.type} ${f.name}`);
  const mixin = JAVA.mixins[r.name];
  const head = `${T}public record ${r.name}(`;
  const tail = `)${mixin ? ` implements ProtocolSupport.${mixin}` : ''} {`;
  // wrap components at ~140 columns
  const lines: string[] = [];
  let line = head;
  comps.forEach((c, i) => {
    const piece = c + (i < comps.length - 1 ? ',' : '');
    if (line !== head && line.length + 1 + piece.length > 140) {
      lines.push(line);
      line = `${T}${T}${piece}`;
    } else {
      line = line === head || line.endsWith('(') ? line + piece : `${line} ${piece}`;
    }
  });
  lines.push(line + tail);
  out.push(...lines);
  const norms = r.fields.filter((f) => f.norm);
  if (norms.length) {
    out.push(`${T}${T}public ${r.name} {`);
    for (const f of norms) out.push(`${T}${T}${T}${f.norm}`);
    out.push(`${T}${T}}`);
  }
  out.push(`${T}}`);
  return out;
}

function build(): string {
  const o: string[] = [];
  o.push('// GENERATED by `npm run gen:java-protocol` (foreman/scripts/gen-java-protocol.ts) from foreman/src/protocol.ts.');
  o.push('// Do not edit by hand: change the zod schemas (or the JAVA table in the script) and regenerate.');
  o.push('// Hand-written behaviour (helpers, ack results without a schema) lives in ProtocolSupport.java.');
  o.push('package dev.agentcraft.client.foreman;');
  o.push('');
  o.push('import com.google.gson.JsonElement;');
  o.push('import com.google.gson.JsonObject;');
  o.push('import com.google.gson.annotations.SerializedName;');
  o.push('import java.util.List;');
  o.push('import java.util.Locale;');
  o.push('import java.util.Map;');
  o.push('import org.jspecify.annotations.Nullable;');
  o.push('');
  o.push('/**');
  o.push(' * Java mirror of the Foreman protocol v' + P.PROTOCOL_VERSION + ' (docs/protocol.md). Field names are exact. Optional fields are');
  o.push(' * nullable (boxed when numeric/boolean); required lists and maps are never null (normalised to empty). Unknown');
  o.push(' * JSON fields are ignored and unknown enum values map to {@code UNKNOWN}, so a newer Foreman never breaks the mod.');
  o.push(' */');
  o.push('public final class Protocol {');
  o.push(`${T}public static final int VERSION = ${P.PROTOCOL_VERSION};`);
  o.push('');
  o.push(`${T}private Protocol() {`);
  o.push(`${T}}`);
  o.push('');
  o.push(`${T}/** Marker for protocol enums: wire value = lower-case constant name unless overridden; unknown values map to UNKNOWN. */`);
  o.push(`${T}public interface Wire {`);
  o.push(`${T}${T}default String wire() {`);
  o.push(`${T}${T}${T}return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);`);
  o.push(`${T}${T}}`);
  o.push(`${T}}`);
  o.push('');
  o.push(`${T}// ------------------------------------------------------------------ option labels`);
  o.push('');
  const opts = [...P.MERGE_OPTIONS, ...P.PERMISSION_OPTIONS];
  for (const label of opts) o.push(`${T}public static final String ${JAVA.optionNames[label] ?? constName(label)} = ${JSON.stringify(label)};`);
  o.push(`${T}/** Merge decision option labels, in order. */`);
  o.push(`${T}public static final List<String> MERGE_OPTIONS = List.of(${P.MERGE_OPTIONS.map((l) => JAVA.optionNames[l] ?? constName(l)).join(', ')});`);
  o.push(`${T}/** Permission decision option labels, in order. */`);
  o.push(`${T}public static final List<String> PERMISSION_OPTIONS = List.of(${P.PERMISSION_OPTIONS.map((l) => JAVA.optionNames[l] ?? constName(l)).join(', ')});`);
  o.push('');
  o.push(`${T}// ------------------------------------------------------------------ enums`);
  for (const n of order) {
    const t = types.get(n)!;
    if (t.kind !== 'enum') continue;
    o.push('');
    o.push(...emitEnum(t));
  }
  o.push('');
  o.push(`${T}// ------------------------------------------------------------------ entities and messages`);
  for (const n of order) {
    const t = types.get(n)!;
    if (t.kind !== 'record') continue;
    o.push('');
    o.push(...emitRecord(t));
  }
  o.push('');
  o.push(`${T}// ------------------------------------------------------------------ message types`);
  o.push('');
  const reg = (title: string, name: string, entries: [string, string][]) => {
    o.push(`${T}/** ${title}: message {@code type} -> record (the envelope fields v, type, id are not part of the records). */`);
    o.push(`${T}public static final Map<String, Class<? extends Record>> ${name} = Map.ofEntries(`);
    entries.forEach(([type, cls], i) => o.push(`${T}${T}Map.entry(${JSON.stringify(type)}, ${cls}.class)${i < entries.length - 1 ? ',' : ');'}`));
  };
  reg('Foreman -> mod', 'SERVER_MESSAGES', serverNames);
  o.push('');
  reg('Mod -> Foreman', 'CLIENT_MESSAGES', clientNames);
  o.push('}');
  return o.join('\n') + '\n';
}

function fixtures(): string {
  return JSON.stringify({ server: SERVER_EXAMPLES, client: CLIENT_EXAMPLES }, null, 2) + '\n';
}

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const outputs: [string, string][] = [
  [path.join(root, 'mod', 'src', 'client', 'java', 'dev', 'agentcraft', 'client', 'foreman', 'Protocol.java'), build()],
  [path.join(root, 'mod', 'src', 'test', 'resources', 'protocol-examples.json'), fixtures()],
];
if (process.argv.includes('--check')) {
  const stale = outputs.filter(([file, text]) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : '') !== text);
  if (stale.length) {
    for (const [file] of stale) console.error(`${path.relative(root, file)} is out of date: run npm run gen:java-protocol`);
    process.exit(1);
  }
  console.log('Protocol.java and protocol-examples.json are up to date');
} else {
  for (const [file, text] of outputs) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    console.log(`wrote ${path.relative(root, file)} (${text.length} chars)`);
  }
}
