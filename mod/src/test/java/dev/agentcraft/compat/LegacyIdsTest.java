package dev.agentcraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.TemplateGrid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/** The agentcraft -> agentcraft_worlds rename: data written before it still loads (docs/FORK.md "Mod id"). */
class LegacyIdsTest {
	@Test
	void modIdIsTheForksOwn() {
		assertEquals("agentcraft_worlds", AgentCraft.MOD_ID);
		assertEquals("agentcraft", LegacyIds.LEGACY_NAMESPACE);
	}

	@Test
	void legacyIdsMoveToTheNewNamespaceAndNothingElseDoes() {
		assertEquals("agentcraft_worlds:monitor", LegacyIds.id("agentcraft:monitor"));
		assertEquals("agentcraft_worlds:agent", LegacyIds.id("agentcraft:agent"));
		assertEquals("agentcraft_worlds:monitor", LegacyIds.id("agentcraft_worlds:monitor"));
		assertEquals("minecraft:stone", LegacyIds.id("minecraft:stone"));
		assertEquals("agentcraft:", LegacyIds.id("agentcraft:"));
		assertEquals("agentcraftx:monitor", LegacyIds.id("agentcraftx:monitor"));
		assertNull(LegacyIds.id(null));
		assertTrue(LegacyIds.isLegacy("agentcraft:task_board"));
		assertFalse(LegacyIds.isLegacy("agentcraft_worlds:task_board"));
	}

	private static CompoundTag state(String name, String facing) {
		CompoundTag s = new CompoundTag();
		s.putString("Name", name);
		if (facing != null) {
			CompoundTag p = new CompoundTag();
			p.putString("facing", facing);
			s.put("Properties", p);
		}
		return s;
	}

	/** A pre-rename structure template, as a user or generated blueprint in <gameDir>/agentcraft/blueprints has it. */
	private static CompoundTag legacyTemplate() {
		CompoundTag t = new CompoundTag();
		ListTag palette = new ListTag();
		palette.add(state("minecraft:stone", null));
		palette.add(state("agentcraft:monitor", "north"));
		palette.add(state("agentcraft:status_lamp", null));
		t.put("palette", palette);
		ListTag blocks = new ListTag();
		CompoundTag b = new CompoundTag();
		b.putInt("state", 1);
		CompoundTag be = new CompoundTag();
		be.putString("id", "agentcraft:monitor");
		be.putString("binding", "agentcraft:not_an_id_field");
		b.put("nbt", be);
		blocks.add(b);
		t.put("blocks", blocks);
		ListTag entities = new ListTag();
		CompoundTag e = new CompoundTag();
		CompoundTag en = new CompoundTag();
		en.putString("id", "agentcraft:agent");
		e.put("nbt", en);
		entities.add(e);
		t.put("entities", entities);
		return t;
	}

	@Test
	void templatePalettesBlockEntitiesAndEntitiesAreRemapped() {
		CompoundTag t = legacyTemplate();
		assertEquals(4, LegacyIds.remap(t));
		ListTag palette = t.getListOrEmpty("palette");
		assertEquals("minecraft:stone", palette.getCompoundOrEmpty(0).getStringOr("Name", ""));
		assertEquals("agentcraft_worlds:monitor", palette.getCompoundOrEmpty(1).getStringOr("Name", ""));
		assertEquals("north", palette.getCompoundOrEmpty(1).getCompoundOrEmpty("Properties").getStringOr("facing", ""));
		assertEquals("agentcraft_worlds:status_lamp", palette.getCompoundOrEmpty(2).getStringOr("Name", ""));
		CompoundTag be = t.getListOrEmpty("blocks").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt");
		assertEquals("agentcraft_worlds:monitor", be.getStringOr("id", ""));
		assertEquals("agentcraft:not_an_id_field", be.getStringOr("binding", ""));
		assertEquals("agentcraft_worlds:agent", t.getListOrEmpty("entities").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt").getStringOr("id", ""));
		// idempotent
		assertEquals(0, LegacyIds.remap(t));
	}

	@Test
	void randomPaletteTemplatesAreRemappedToo() {
		CompoundTag t = new CompoundTag();
		ListTag palettes = new ListTag();
		ListTag p0 = new ListTag();
		p0.add(state("agentcraft:walnut_panel", null));
		ListTag p1 = new ListTag();
		p1.add(state("agentcraft:plaster_panel", null));
		palettes.add(p0);
		palettes.add(p1);
		t.put("palettes", palettes);
		assertEquals(2, LegacyIds.remap(t));
		assertEquals("agentcraft_worlds:plaster_panel", t.getListOrEmpty("palettes").getListOrEmpty(1).getCompoundOrEmpty(0).getStringOr("Name", ""));
	}

	@Test
	void journalEntriesKeepTheirOwnIdsAndRemapTheirStates() {
		CompoundTag entry = new CompoundTag();
		entry.putString("id", "b3");
		entry.putString("dimension", "minecraft:overworld");
		ListTag pal = new ListTag();
		pal.add(state("agentcraft:task_board", "east"));
		entry.put("palette", pal);
		CompoundTag an = new CompoundTag();
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", "agentcraft:task_board");
		an.put("0", nbt);
		entry.put("an", an);
		assertEquals(2, LegacyIds.remap(entry));
		assertEquals("b3", entry.getStringOr("id", ""));
		assertEquals("agentcraft_worlds:task_board", entry.getListOrEmpty("palette").getCompoundOrEmpty(0).getStringOr("Name", ""));
		assertEquals("agentcraft_worlds:task_board", entry.getCompoundOrEmpty("an").getCompoundOrEmpty("0").getStringOr("id", ""));
	}

	@Test
	void pinsFromBeforeTheRenameStillMatch() {
		int[] xyz = {0, 0, 0, 1, 0, 0};
		boolean[] be = {false, true};
		String before = TemplateGrid.fingerprint(xyz, new String[] {"Block{minecraft:stone}", "Block{agentcraft:monitor}[facing=north]"}, be);
		String after = TemplateGrid.fingerprint(xyz, new String[] {"Block{minecraft:stone}", "Block{agentcraft_worlds:monitor}[facing=north]"}, be);
		assertEquals(before, after);
		assertNotEquals(before, TemplateGrid.fingerprint(xyz, new String[] {"Block{minecraft:stone}", "Block{minecraft:barrel}[facing=north]"}, be));
	}

	@Test
	void sidecarBlockIdsAreRemapped() throws IOException {
		JsonObject o = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/agentcraft_worlds/blueprints/workshop.blueprint.json")))
			.getAsJsonObject();
		o.addProperty("foundationBlock", "agentcraft:plaster_panel");
		JsonObject approach = o.has("approach") ? o.getAsJsonObject("approach") : new JsonObject();
		approach.addProperty("block", "agentcraft:terracotta_tile");
		approach.addProperty("slab", "minecraft:stone_brick_slab");
		o.add("approach", approach);
		Blueprint bp = Blueprint.fromJson(o);
		assertEquals("agentcraft_worlds:plaster_panel", bp.foundationBlock());
		assertEquals("agentcraft_worlds:terracotta_tile", bp.approach().block());
		assertEquals("minecraft:stone_brick_slab", bp.approach().slab());
	}
}
