package dev.agentcraft.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Journal values against the game's own block state NBT (26.x writes and reads {@code {id, properties}}): the journal's
 * helpers ({@link Value#of}, {@link Value#name}, {@link Journal#AIR}) agree with what {@code NbtUtils.writeBlockState}
 * writes, and values in the older form {@code {Name, Properties}} (snapshots or journals written before 26.x) are read
 * as the same block instead of decoding to air.
 */
class JournalValueTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static Value world(BlockState s) {
		return new Value(NbtUtils.writeBlockState(s), null);
	}

	static BlockState decode(Value v) {
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, v.state());
	}

	static CompoundTag old(String name, String... props) {
		CompoundTag t = new CompoundTag();
		t.putString("Name", name);
		if (props.length > 0) {
			CompoundTag p = new CompoundTag();
			for (int i = 0; i + 1 < props.length; i += 2) {
				p.putString(props[i], props[i + 1]);
			}
			t.put("Properties", p);
		}
		return t;
	}

	@Test
	void helpersWriteWhatTheGameWrites() {
		assertEquals(world(Blocks.AIR.defaultBlockState()), Journal.AIR, "Journal.AIR is the world's air");
		BlockState top = Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
		Value v = world(top);
		assertEquals("minecraft:sunflower", v.name(), "name() of a value read from the world");
		assertEquals("upper", v.property("half"));
		assertEquals(Value.of("minecraft:sunflower", "half", "upper"), v);
		assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), decode(Value.of("minecraft:stone_bricks")), "a helper value decodes to its block");
		assertTrue(v.toString().startsWith("minecraft:sunflower{"), v.toString());
	}

	@Test
	void olderKeysAreReadAsTheSameBlock() {
		CompoundTag raw = old("minecraft:oak_stairs", "facing", "east", "half", "bottom", "shape", "straight", "waterlogged", "false");
		assertEquals(Blocks.AIR.defaultBlockState(), NbtUtils.readBlockState(BuiltInRegistries.BLOCK, raw),
			"26.x reads {Name, Properties} as air: why values are converted");
		Value v = new Value(raw, null);
		assertEquals("minecraft:oak_stairs", v.name());
		assertEquals(world(Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,
			net.minecraft.core.Direction.EAST)), v, "equal to the world's value of the same state");
		assertEquals("east", decode(v).getValue(net.minecraft.world.level.block.StairBlock.FACING).getSerializedName());
		assertTrue(raw.contains("Name"), "the tag it was made from is not changed");
		CompoundTag current = NbtUtils.writeBlockState(Blocks.DIRT.defaultBlockState());
		assertSame(current, new Value(current, null).state(), "a 26.x state is kept as it is (no copy)");
		assertNotSame(raw, v.state());
		assertEquals(Journal.AIR, new Value(old("minecraft:air"), null));
	}

	@Test
	void anOlderJournalFileDecodesToTheSameValues() {
		long p = Journal.pos(3, 64, -7);
		Entry e = new Entry("j1", "building", "b1", "minecraft:overworld", Policy.BOX, 1L, Status.ACTIVE,
			List.of(new Cell(p, 1, Value.of("minecraft:grass_block", "snowy", "false"), Value.of("minecraft:stone_bricks"))), null, null);
		CompoundTag t = JournalNbt.encode(e);
		ListTag pal = t.getListOrEmpty("palette");
		for (int i = 0; i < pal.size(); i++) {
			assertTrue(pal.getCompoundOrEmpty(i).contains("id"), "written in the 26.x form: " + pal);
			assertFalse(pal.getCompoundOrEmpty(i).contains("Name"));
		}
		// the same file as an older build would have written it
		ListTag olderPal = new ListTag();
		olderPal.add(old("minecraft:grass_block", "snowy", "false"));
		olderPal.add(old("minecraft:stone_bricks"));
		t.put("palette", olderPal);
		Entry back = JournalNbt.decode(t);
		assertEquals(e.cells(), back.cells());
		assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), decode(back.cells().get(0).before()));
	}

	@Test
	void anOlderSnapshotTemplateReadsAsItsBlocks() {
		CompoundTag tpl = new CompoundTag();
		ListTag palette = new ListTag();
		palette.add(old("minecraft:tall_grass", "half", "lower"));
		palette.add(old("minecraft:tall_grass", "half", "upper"));
		ListTag blocks = new ListTag();
		for (int y = 0; y < 2; y++) {
			CompoundTag b = new CompoundTag();
			ListTag pos = new ListTag();
			pos.add(IntTag.valueOf(0));
			pos.add(IntTag.valueOf(y));
			pos.add(IntTag.valueOf(0));
			b.put("pos", pos);
			b.putInt("state", y);
			blocks.add(b);
		}
		tpl.put("palette", palette);
		tpl.put("blocks", blocks);
		Map<Long, Value> v = JournalNbt.values(tpl, 5, 70, 5);
		assertEquals(world(Blocks.TALL_GRASS.defaultBlockState()), v.get(Journal.pos(5, 70, 5)));
		assertEquals(Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), decode(v.get(Journal.pos(5, 71, 5))));
		// and the template a restore pastes from them is in the 26.x form
		CompoundTag again = JournalNbt.toTemplate(v, 5, 70, 5, 1, 2, 1, 0);
		assertEquals("minecraft:tall_grass", again.getListOrEmpty("palette").getCompoundOrEmpty(0).getStringOr("id", ""));
	}
}
