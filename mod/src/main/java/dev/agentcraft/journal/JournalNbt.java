package dev.agentcraft.journal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.HandDown;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Undo;
import dev.agentcraft.journal.Journal.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The world journal's NBT, pure (no world, no registries): an entry file, and conversion from and to the structure
 * template format the buildings' snapshots always used ({@code size}, {@code palette} of {@code {id, properties}}: 26.x's
 * keys; an older snapshot's {@code {Name, Properties}} are read too, see {@link Value#canonical}),
 * {@code blocks} of {@code {pos, state, nbt?}}), so a building's site is still captured and restored by vanilla's
 * {@code StructureTemplate} exactly as before.
 *
 * <pre>
 * entry file: { version: 1, id, kind, owner, dimension, policy, createdAt, status, meta?: "json",
 *   palette: [state...], pos: long[], layer: long[], b: int[] (palette), a: int[] (palette, -1 = unknown),
 *   bn: { "&lt;i&gt;": nbt }, an: { "&lt;i&gt;": nbt },
 *   undo?: { group, at, w: int[] (palette, -1 = nothing written), wn: {..}, handed: [{order, to, pos, was, now}], hn: {"&lt;k&gt;w"|"&lt;k&gt;n": nbt} } }
 * </pre>
 */
public final class JournalNbt {
	public static final int VERSION = 1;

	private JournalNbt() {
	}

	/** A palette of block states (by NBT equality) for one file. */
	private static final class Palette {
		final ListTag list = new ListTag();
		final Map<CompoundTag, Integer> ids = new HashMap<>();

		int id(CompoundTag state) {
			Integer i = ids.get(state);
			if (i == null) {
				i = list.size();
				ids.put(state, i);
				list.add(state);
			}
			return i;
		}
	}

	public static CompoundTag encode(Entry e) {
		CompoundTag t = new CompoundTag();
		t.putInt("version", VERSION);
		t.putString("id", e.id());
		t.putString("kind", e.kind());
		t.putString("owner", e.owner());
		t.putString("dimension", e.dimension());
		t.putString("policy", e.policy().name());
		t.putLong("createdAt", e.createdAt());
		t.putString("status", e.status().name());
		if (e.meta() != null) {
			t.putString("meta", e.meta().toString());
		}
		Palette pal = new Palette();
		int n = e.cells().size();
		long[] pos = new long[n];
		long[] layer = new long[n];
		int[] b = new int[n];
		int[] a = new int[n];
		CompoundTag bn = new CompoundTag();
		CompoundTag an = new CompoundTag();
		Map<Long, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++) {
			Cell c = e.cells().get(i);
			pos[i] = c.pos();
			layer[i] = c.layer();
			index.put(c.pos(), i);
			b[i] = pal.id(c.before().state());
			if (c.before().nbt() != null) {
				bn.put(Integer.toString(i), c.before().nbt());
			}
			if (c.after() == null) {
				a[i] = -1;
			} else {
				a[i] = pal.id(c.after().state());
				if (c.after().nbt() != null) {
					an.put(Integer.toString(i), c.after().nbt());
				}
			}
		}
		t.putLongArray("pos", pos);
		t.putLongArray("layer", layer);
		t.putIntArray("b", b);
		t.putIntArray("a", a);
		t.put("bn", bn);
		t.put("an", an);
		Undo u = e.undo();
		if (u != null) {
			CompoundTag ut = new CompoundTag();
			ut.putString("group", u.group());
			ut.putLong("at", u.at());
			int[] w = new int[n];
			java.util.Arrays.fill(w, -1);
			CompoundTag wn = new CompoundTag();
			ListTag extra = new ListTag(); // written values at positions that are not this entry's cells (never, but kept lossless)
			for (var x : u.written().entrySet()) {
				Integer i = index.get(x.getKey());
				if (i == null) {
					CompoundTag o = new CompoundTag();
					o.putLong("pos", x.getKey());
					o.putInt("v", pal.id(x.getValue().state()));
					if (x.getValue().nbt() != null) {
						o.put("nbt", x.getValue().nbt());
					}
					extra.add(o);
					continue;
				}
				w[i] = pal.id(x.getValue().state());
				if (x.getValue().nbt() != null) {
					wn.put(Integer.toString(i), x.getValue().nbt());
				}
			}
			ut.putIntArray("w", w);
			ut.put("wn", wn);
			if (!extra.isEmpty()) {
				ut.put("wx", extra);
			}
			ListTag hs = new ListTag();
			CompoundTag hn = new CompoundTag();
			for (int k = 0; k < u.handed().size(); k++) {
				HandDown h = u.handed().get(k);
				CompoundTag ht = new CompoundTag();
				ht.putInt("order", h.order());
				ht.putString("to", h.to());
				ht.putLong("pos", h.pos());
				ht.putInt("was", pal.id(h.was().state()));
				ht.putInt("now", pal.id(h.now().state()));
				if (h.was().nbt() != null) {
					hn.put(k + "w", h.was().nbt());
				}
				if (h.now().nbt() != null) {
					hn.put(k + "n", h.now().nbt());
				}
				hs.add(ht);
			}
			ut.put("handed", hs);
			ut.put("hn", hn);
			t.put("undo", ut);
		}
		t.put("palette", pal.list);
		return t;
	}

	/** Reads {@link #encode} output; throws IllegalArgumentException when it is not an entry. */
	public static Entry decode(CompoundTag t) {
		if (t.getIntOr("version", 0) != VERSION) {
			throw new IllegalArgumentException("not a journal entry (version " + t.getIntOr("version", 0) + ")");
		}
		ListTag palList = t.getListOrEmpty("palette");
		List<CompoundTag> pal = new ArrayList<>(palList.size());
		for (int i = 0; i < palList.size(); i++) {
			pal.add(Value.canonical(palList.getCompoundOrEmpty(i))); // once per state, not per cell (an older journal's {Name, ...})
		}
		long[] pos = t.getLongArray("pos").orElse(new long[0]);
		long[] layer = t.getLongArray("layer").orElse(new long[pos.length]);
		int[] b = t.getIntArray("b").orElse(new int[0]);
		int[] a = t.getIntArray("a").orElse(new int[0]);
		if (b.length != pos.length || a.length != pos.length || layer.length != pos.length) {
			throw new IllegalArgumentException("journal entry " + t.getStringOr("id", "?") + ": cell arrays differ in length");
		}
		CompoundTag bn = t.getCompoundOrEmpty("bn");
		CompoundTag an = t.getCompoundOrEmpty("an");
		List<Cell> cells = new ArrayList<>(pos.length);
		for (int i = 0; i < pos.length; i++) {
			Value before = new Value(pal.get(b[i]), bn.getCompound(Integer.toString(i)).orElse(null));
			Value after = a[i] < 0 ? null : new Value(pal.get(a[i]), an.getCompound(Integer.toString(i)).orElse(null));
			cells.add(new Cell(pos[i], layer[i], before, after));
		}
		Undo undo = null;
		if (t.getCompound("undo").isPresent()) {
			CompoundTag ut = t.getCompoundOrEmpty("undo");
			int[] w = ut.getIntArray("w").orElse(new int[0]);
			CompoundTag wn = ut.getCompoundOrEmpty("wn");
			Map<Long, Value> written = new LinkedHashMap<>();
			for (int i = 0; i < w.length && i < pos.length; i++) {
				if (w[i] >= 0) {
					written.put(pos[i], new Value(pal.get(w[i]), wn.getCompound(Integer.toString(i)).orElse(null)));
				}
			}
			ListTag wx = ut.getListOrEmpty("wx");
			for (int i = 0; i < wx.size(); i++) {
				CompoundTag o = wx.getCompoundOrEmpty(i);
				written.put(o.getLongOr("pos", 0L), new Value(pal.get(o.getIntOr("v", 0)), o.getCompound("nbt").orElse(null)));
			}
			ListTag hs = ut.getListOrEmpty("handed");
			CompoundTag hn = ut.getCompoundOrEmpty("hn");
			List<HandDown> handed = new ArrayList<>();
			for (int k = 0; k < hs.size(); k++) {
				CompoundTag ht = hs.getCompoundOrEmpty(k);
				handed.add(new HandDown(ht.getIntOr("order", k), ht.getStringOr("to", ""), ht.getLongOr("pos", 0L),
					new Value(pal.get(ht.getIntOr("was", 0)), hn.getCompound(k + "w").orElse(null)),
					new Value(pal.get(ht.getIntOr("now", 0)), hn.getCompound(k + "n").orElse(null))));
			}
			undo = new Undo(ut.getStringOr("group", t.getStringOr("id", "")), ut.getLongOr("at", 0L), written, handed);
		}
		JsonObject meta = null;
		String m = t.getStringOr("meta", "");
		if (!m.isEmpty()) {
			meta = JsonParser.parseString(m).getAsJsonObject();
		}
		return new Entry(t.getStringOr("id", ""), t.getStringOr("kind", ""), t.getStringOr("owner", ""), t.getStringOr("dimension", "minecraft:overworld"),
			Policy.valueOf(t.getStringOr("policy", "BOX")), t.getLongOr("createdAt", 0L), Status.valueOf(t.getStringOr("status", "ACTIVE")), cells, undo,
			meta);
	}

	// ------------------------------------------------------------------ structure templates

	/**
	 * The cells of a site captured as a structure template (relative to {@code (minX, minY, minZ)}), in the template's
	 * block order: {@code before} from {@code beforeTpl}, {@code after} from {@code afterTpl} at the same position (null:
	 * unknown). Pure.
	 */
	public static List<Cell> fromTemplate(CompoundTag beforeTpl, @Nullable CompoundTag afterTpl, int minX, int minY, int minZ, long layer) {
		Map<Long, Value> after = afterTpl == null ? Map.of() : values(afterTpl, minX, minY, minZ);
		List<Cell> out = new ArrayList<>();
		for (var e : values(beforeTpl, minX, minY, minZ).entrySet()) {
			out.add(new Cell(e.getKey(), layer, e.getValue(), after.get(e.getKey())));
		}
		return out;
	}

	/** A structure template's blocks as world position -> value, in its block order. Pure. */
	public static LinkedHashMap<Long, Value> values(CompoundTag tpl, int minX, int minY, int minZ) {
		ListTag palette = tpl.getListOrEmpty("palette");
		ListTag blocks = tpl.getListOrEmpty("blocks");
		List<CompoundTag> states = new ArrayList<>(palette.size());
		for (int i = 0; i < palette.size(); i++) {
			states.add(Value.canonical(palette.getCompoundOrEmpty(i))); // a snapshot written before 26.x: {Name, Properties}
		}
		LinkedHashMap<Long, Value> out = new LinkedHashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag bt = blocks.getCompoundOrEmpty(i);
			ListTag p = bt.getListOrEmpty("pos");
			long pos = Journal.pos(minX + p.getIntOr(0, 0), minY + p.getIntOr(1, 0), minZ + p.getIntOr(2, 0));
			int si = bt.getIntOr("state", 0);
			CompoundTag state = si >= 0 && si < states.size() ? states.get(si) : new CompoundTag();
			out.put(pos, new Value(state, bt.getCompound("nbt").orElse(null)));
		}
		return out;
	}

	/**
	 * A structure template (the format {@code StructureTemplate.load} reads) of {@code values} (world position -> value,
	 * in the order given) relative to {@code (minX, minY, minZ)}, {@code size} big. {@code dataVersion} is copied when
	 * given. Pure.
	 */
	public static CompoundTag toTemplate(Map<Long, Value> values, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, int dataVersion) {
		CompoundTag t = new CompoundTag();
		Palette pal = new Palette();
		ListTag blocks = new ListTag();
		for (var e : values.entrySet()) {
			CompoundTag bt = new CompoundTag();
			ListTag p = new ListTag();
			p.add(IntTag.valueOf(Journal.x(e.getKey()) - minX));
			p.add(IntTag.valueOf(Journal.y(e.getKey()) - minY));
			p.add(IntTag.valueOf(Journal.z(e.getKey()) - minZ));
			bt.put("pos", p);
			bt.putInt("state", pal.id(e.getValue().state()));
			if (e.getValue().nbt() != null) {
				bt.put("nbt", e.getValue().nbt());
			}
			blocks.add(bt);
		}
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(sizeX));
		size.add(IntTag.valueOf(sizeY));
		size.add(IntTag.valueOf(sizeZ));
		t.put("size", size);
		t.put("blocks", blocks);
		t.put("palette", pal.list);
		t.put("entities", new ListTag());
		if (dataVersion > 0) {
			t.putInt("DataVersion", dataVersion);
		}
		return t;
	}

	/** The befores of an entry's cells as a template over {@code box} {minX..maxZ} (crash checks compare a site with it). Pure. */
	public static CompoundTag beforeTemplate(Entry e, int[] box) {
		Map<Long, Value> v = new LinkedHashMap<>();
		for (Cell c : e.cells()) {
			v.put(c.pos(), c.before());
		}
		return toTemplate(v, box[0], box[1], box[2], box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1, 0);
	}

	/** Whether {@code t} is a tag list of block compounds (a template's {@code blocks}); used by migration checks. */
	static boolean isTemplate(CompoundTag t) {
		return t.get("blocks") instanceof ListTag && t.get("palette") instanceof ListTag && t.get("size") instanceof ListTag;
	}

	static @Nullable Tag get(CompoundTag t, String key) {
		return t.get(key);
	}
}
