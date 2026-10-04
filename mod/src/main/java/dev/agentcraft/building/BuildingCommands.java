package dev.agentcraft.building;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Commands (gamemaster, under {@code /agentcraft}):
 * <pre>
 * blueprints                       list loaded blueprints
 * blueprints reload                reload bundled + user blueprints
 * buildings                        list buildings in this world
 * place &lt;blueprint&gt; &lt;repo&gt;[,&lt;repo&gt;...] [rotation] [force]
 *                                  place in front of the player (ground row at the feet, entrance
 *                                  facing the player, its approach ending 2 blocks ahead; rotation
 *                                  overrides the automatic one)
 * remove &lt;id&gt; [forget|force]       restore the area (forget: only drop the record; force: although the
 *                                  player's things are inside, which are lost)
 * repos &lt;id&gt; &lt;repo&gt;[,&lt;repo&gt;...]    give a building other repos (wing n = the n-th)
 * home &lt;id&gt;                        make a building home
 * build                            open the placement wizard (singleplayer client; see {@link #wizardOpener})
 * </pre>
 * The arguments after {@code <blueprint>} are one greedy string parsed here, so repo ids with commas,
 * dots or slashes need no quotes.
 */
public final class BuildingCommands {
	/** Blocks between the player and the near edge of a building placed in front of them. */
	public static final int GAP = 2;

	/**
	 * Opens the placement wizard for the player who ran {@code /agentcraft build}. The AgentCraft client
	 * installs it at start (it hops to the client thread itself); it stays null on a dedicated server,
	 * where the wizard does not exist (no networking) and {@code /agentcraft place} is the way.
	 */
	public static volatile @Nullable Consumer<ServerPlayer> wizardOpener;

	private BuildingCommands() {
	}

	public static void init() {
		AgentCraftCommands.sub(root -> root
			.then(Commands.literal("blueprints")
				.executes(BuildingCommands::listBlueprints)
				.then(Commands.literal("reload").executes(ctx -> {
					Blueprints.reload(ctx.getSource().getServer());
					List<String> problems = Blueprints.lastProblems();
					ctx.getSource().sendSuccess(() -> Component.literal("Reloaded " + Blueprints.ids().size() + " blueprint(s) "
						+ Blueprints.ids() + (problems.isEmpty() ? "" : "; skipped " + problems.size() + ":")), false);
					for (String p : problems) {
						ctx.getSource().sendFailure(Component.literal("  " + p));
					}
					return Blueprints.ids().size();
				})))
			.then(Commands.literal("buildings").executes(BuildingCommands::listBuildings))
			.then(Commands.literal("build").executes(BuildingCommands::build))
			.then(Commands.literal("place")
				.then(Commands.argument("blueprint", StringArgumentType.word())
					.suggests((ctx, b) -> {
						Blueprints.ids().forEach(b::suggest);
						return b.buildFuture();
					})
					.executes(BuildingCommands::place) // a fixture needs no further arguments
					.then(Commands.argument("args", StringArgumentType.greedyString())
						.executes(BuildingCommands::place))))
			.then(Commands.literal("remove")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, b) -> {
						Buildings.all().forEach(x -> b.suggest(x.id())); // fixtures are removed the same way
						return b.buildFuture();
					})
					.executes(ctx -> remove(ctx, false, false))
					.then(Commands.literal("forget").executes(ctx -> remove(ctx, true, false)))
					.then(Commands.literal("force").executes(ctx -> remove(ctx, false, true)))))
			.then(Commands.literal("repos")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, b) -> {
						Buildings.buildings().forEach(x -> b.suggest(x.id()));
						return b.buildFuture();
					})
					.then(Commands.argument("repos", StringArgumentType.greedyString()).executes(BuildingCommands::repos))))
			.then(Commands.literal("home")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, b) -> {
						Buildings.buildings().forEach(x -> b.suggest(x.id()));
						return b.buildFuture();
					})
					.executes(BuildingCommands::home))));
	}

	private static int listBlueprints(CommandContext<CommandSourceStack> ctx) {
		var all = Blueprints.all();
		ctx.getSource().sendSuccess(() -> Component.literal(all.size() + " blueprint(s)" + (all.isEmpty()
			? " (bundled: data/agentcraft/blueprints; yours: " + Blueprints.userDir() + ")" : ":")), false);
		for (Blueprint bp : all) {
			var e = Blueprints.entry(bp.id());
			ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s  \"%s\"  %s%s  %dx%dx%d  front %s  [%s]",
				bp.id(), bp.name(), bp.kind(), bp.isGroup() ? " (" + bp.wings() + " wings)" : "", bp.sizeX(), bp.sizeY(), bp.sizeZ(), bp.front(),
				e == null ? "?" : e.source().startsWith("user") ? "user" : "bundled")), false);
		}
		return all.size();
	}

	private static int build(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Consumer<ServerPlayer> opener = wizardOpener;
		ServerPlayer player = src.getPlayer();
		if (opener == null || player == null || !src.getServer().isSingleplayerOwner(player.nameAndId())) {
			src.sendFailure(Component.literal("The building wizard runs in the AgentCraft client in singleplayer; here use "
				+ "/agentcraft place <blueprint> <repo>[,<repo>...] [rotation] [force]"));
			return 0;
		}
		opener.accept(player);
		return 1;
	}

	private static int listBuildings(CommandContext<CommandSourceStack> ctx) {
		var all = Buildings.all();
		int fixtures = Buildings.fixtures().size();
		ctx.getSource().sendSuccess(() -> Component.literal((all.size() - fixtures) + " building(s)" + (fixtures > 0 ? ", " + fixtures + " fixture(s)" : "")
			+ (all.isEmpty() ? "" : ":")), false);
		for (Building b : all) {
			ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s%s  %s  %s  %s  box %s  %d anchors",
				b.id(), b.home() ? " (home)" : "", b.blueprint(), b.isFixture() ? "fixture" : "repos " + String.join(",", b.repos()), b.rotation(), Buildings.str(b.box()),
				b.anchors().size())), false);
			Buildings.Report r = Buildings.reports().get(b.id());
			if (r != null) {
				ctx.getSource().sendSuccess(() -> Component.literal("    " + (r.problem() ? "check: " : "note: ") + r.message()), false);
			}
		}
		return all.size();
	}

	private static int place(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String bpId = StringArgumentType.getString(ctx, "blueprint");
		Blueprint bp = Blueprints.get(bpId);
		if (bp == null) {
			src.sendFailure(Component.literal("Unknown blueprint '" + bpId + "' (known: " + Blueprints.ids() + ")"));
			return 0;
		}
		String args;
		try {
			args = StringArgumentType.getString(ctx, "args").trim();
		} catch (IllegalArgumentException none) {
			args = ""; // "/agentcraft place <blueprint>" alone
		}
		if (args.isEmpty() && !bp.isFixture()) {
			src.sendFailure(Component.literal("Name the repo(s): /agentcraft place " + bpId + " <repo>[,<repo>...] [rotation] [force]"));
			return 0;
		}
		String[] tokens = args.isEmpty() ? new String[] {"-"} : args.split("\\s+");
		// a fixture (village board) takes no repos: "/agentcraft place village_board - [rotation] [force]" (or no "-")
		boolean noRepos = bp.isFixture() && (tokens[0].equals("-") || tokens[0].equalsIgnoreCase("none"));
		List<String> repos = bp.isFixture() ? List.of() : BlueprintTransform.parseRepos(tokens[0]);
		int turns = -1;
		boolean force = false;
		for (int i = bp.isFixture() && !noRepos ? 0 : 1; i < tokens.length; i++) {
			String t = tokens[i];
			if (t.equalsIgnoreCase("force")) {
				force = true;
			} else if (t.equalsIgnoreCase("auto")) {
				turns = -1;
			} else if (BlueprintTransform.parseTurns(t) >= 0 && turns < 0) {
				turns = BlueprintTransform.parseTurns(t);
			} else {
				src.sendFailure(Component.literal("Unexpected '" + t + "': use /agentcraft place <blueprint> <repo>[,<repo>...] "
					+ "[none|clockwise_90|clockwise_180|counterclockwise_90] [force]"));
				return 0;
			}
		}
		Vec3 pos = src.getPosition();
		Direction facing = src.getEntity() != null ? src.getEntity().getDirection() : Direction.SOUTH;
		if (turns < 0) {
			// the entrance faces the player, i.e. the opposite of where they look
			turns = BlueprintTransform.turnsToFace(bp.front(), facing.getOpposite().getName());
		}
		int rsx = BlueprintTransform.rotatedSizeX(bp.sizeX(), bp.sizeZ(), turns);
		int rsz = BlueprintTransform.rotatedSizeZ(bp.sizeX(), bp.sizeZ(), turns);
		BlockPos feet = BlockPos.containing(pos);
		// the entrance approach lies between the player and a building whose entrance faces them: keep them out of it
		boolean facesPlayer = BlueprintTransform.rotateDirection(bp.front(), turns).equals(facing.getOpposite().getName());
		int gap = GAP + (facesPlayer ? bp.approach().length() : 0);
		int[] o = BlueprintTransform.originInFront(feet.getX(), feet.getY(), feet.getZ(), facing.getName(), rsx, rsz, bp.groundY(), gap);
		ServerLevel level = src.getLevel();
		try {
			Building b = Buildings.place(level, bp, new BlockPos(o[0], o[1], o[2]), Rotation.values()[turns], repos, force);
			String note = Buildings.lastNote();
			src.sendSuccess(() -> Component.literal("Placed " + b.id() + " (" + bp.name() + ")" + (b.isFixture() ? "" : " for " + String.join(", ", b.repos()))
				+ ", "
				+ b.rotation() + ", box " + Buildings.str(b.box()) + (b.home() ? ", home" : "") + (note == null ? "" : " (" + note + ")")
				+ ". Undo: the hub (H) > Buildings > " + b.id() + " > Remove"), true);
			return 1;
		} catch (Buildings.BuildingException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("Placing {} failed", bpId, e);
			src.sendFailure(Component.literal("Placing " + bpId + " failed: " + e));
			return 0;
		}
	}

	private static int repos(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		List<String> repos = BlueprintTransform.parseRepos(StringArgumentType.getString(ctx, "repos"));
		try {
			Building b = Buildings.setRepos(ctx.getSource().getServer(), id, repos);
			ctx.getSource().sendSuccess(() -> Component.literal(id + " now hosts " + String.join(", ", b.repos())), true);
			return 1;
		} catch (Buildings.BuildingException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	private static int remove(CommandContext<CommandSourceStack> ctx, boolean forgetOnly, boolean force) {
		CommandSourceStack src = ctx.getSource();
		String id = StringArgumentType.getString(ctx, "id");
		try {
			if (forgetOnly) {
				Buildings.forget(src.getServer(), id);
				src.sendSuccess(() -> Component.literal("Forgot " + id + "; its blocks stay in the world"), true);
			} else {
				Building b = Buildings.remove(src.getLevel(), id, force);
				src.sendSuccess(() -> Component.literal("Removed " + id + " (" + b.blueprint() + "); restored " + Buildings.str(b.restoreBox())), true);
			}
			return 1;
		} catch (Buildings.BuildingException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("Removing {} failed", id, e);
			src.sendFailure(Component.literal("Removing " + id + " failed: " + e));
			return 0;
		}
	}

	private static int home(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		try {
			Building b = Buildings.setHome(ctx.getSource().getServer(), id);
			ctx.getSource().sendSuccess(() -> Component.literal(b.id() + " (" + b.blueprint() + ") is now home"), true);
			return 1;
		} catch (Buildings.BuildingException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}
}
