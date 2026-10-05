package dev.agentcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.launcher.LauncherPlan.Action;
import dev.agentcraft.launcher.LauncherPlan.Host;
import dev.agentcraft.launcher.LauncherPlan.Owner;
import dev.agentcraft.launcher.LauncherPlan.Probe;
import dev.agentcraft.launcher.LauncherPlan.Running;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LauncherPlanTest {
	private static Host mac(Map<String, String> env, Set<Path> files, Map<Path, List<Path>> dirs) {
		return new Host(env, "Mac OS X", "/Users/u", files::contains, d -> dirs.getOrDefault(d, List.of()));
	}

	// ------------------------------------------------------------------ node discovery

	@Test
	void nodeIsLookedForInOrder() {
		Map<Path, List<Path>> dirs = Map.of(Path.of("/Users/u/.nvm/versions/node"),
			List.of(Path.of("/Users/u/.nvm/versions/node/v20.1.0"), Path.of("/Users/u/.nvm/versions/node/v22.11.0"), Path.of("/Users/u/.nvm/versions/node/v9.0.0")));
		Host h = mac(Map.of("PATH", "/usr/bin:/custom/bin"), Set.of(), dirs);
		List<Path> c = LauncherPlan.nodeCandidates("~/tools/node", h);
		assertEquals(Path.of("/Users/u/tools/node"), c.get(0), "config nodePath first (~ expanded, a binary named node kept)");
		assertEquals(Path.of("/usr/bin/node"), c.get(1), "then PATH, in order");
		assertEquals(Path.of("/custom/bin/node"), c.get(2));
		assertEquals(Path.of("/opt/homebrew/bin/node"), c.get(3), "then Homebrew");
		assertEquals(Path.of("/usr/local/bin/node"), c.get(4));
		assertEquals(Path.of("/Users/u/.local/share/mise/shims/node"), c.get(5), "then mise shims");
		assertEquals(Path.of("/Users/u/.volta/bin/node"), c.get(6), "then volta");
		int v22 = c.indexOf(Path.of("/Users/u/.nvm/versions/node/v22.11.0/bin/node"));
		int v20 = c.indexOf(Path.of("/Users/u/.nvm/versions/node/v20.1.0/bin/node"));
		assertTrue(v22 > 6 && v22 < v20, "nvm installs newest first");
		assertEquals(1, c.stream().filter(p -> p.equals(Path.of("/usr/bin/node"))).count(), "duplicates dropped");
	}

	@Test
	void configNodePathMayBeAFolder() {
		Host h = mac(Map.of(), Set.of(), Map.of());
		assertEquals(Path.of("/opt/n/bin/node"), LauncherPlan.nodeCandidates("/opt/n/bin", h).get(0));
	}

	@Test
	void firstExecutableWins() {
		Host h = mac(Map.of("PATH", "/a:/b"), Set.of(Path.of("/b/node"), Path.of("/opt/homebrew/bin/node")), Map.of());
		assertEquals(Path.of("/b/node"), LauncherPlan.firstExecutable(LauncherPlan.nodeCandidates(null, h), h));
		Host none = mac(Map.of(), Set.of(), Map.of());
		assertNull(LauncherPlan.firstExecutable(LauncherPlan.nodeCandidates(null, none), none));
	}

	@Test
	void windowsCandidates() {
		Host w = new Host(Map.of("Path", "C:\\x", "ProgramFiles", "C:\\PF", "LOCALAPPDATA", "C:\\L"), "Windows 11", "C:\\Users\\u", p -> false,
			d -> List.of());
		List<Path> c = LauncherPlan.nodeCandidates(null, w);
		assertTrue(c.get(0).toString().endsWith("node.exe"));
		assertEquals(List.of("where", "node"), LauncherPlan.shellProbe(w));
		assertTrue(LauncherPlan.loginEnvProbes(w).isEmpty());
	}

	@Test
	void shellProbesUseTheLoginShell() {
		Host h = mac(Map.of("SHELL", "/bin/bash"), Set.of(), Map.of());
		assertEquals(List.of("/bin/bash", "-lc", "command -v node"), LauncherPlan.shellProbe(h));
		Host noShell = mac(Map.of(), Set.of(), Map.of());
		assertEquals("/bin/zsh", LauncherPlan.shellProbe(noShell).get(0));
		List<List<String>> env = LauncherPlan.loginEnvProbes(h);
		assertEquals("-lic", env.get(0).get(1), "interactive login first, like tools/foreman-daemon.sh");
		assertEquals("-lc", env.get(1).get(1), "plain login as the fallback");
		assertTrue(env.get(0).get(2).contains(LauncherPlan.ENV_MARK) && env.get(0).get(2).contains("env -0"));
	}

	@Test
	void probeOutputAndVersions() {
		assertEquals(Path.of("/Users/u/.local/share/mise/shims/node"), LauncherPlan.parseProbe("Welcome!\n/Users/u/.local/share/mise/shims/node\n"));
		assertNull(LauncherPlan.parseProbe("node not found"));
		assertTrue(LauncherPlan.nodeOk("v22.11.0\n"));
		assertTrue(LauncherPlan.nodeOk("v24.16.0"));
		assertFalse(LauncherPlan.nodeOk("v20.19.1"));
		assertFalse(LauncherPlan.nodeOk(null));
		assertFalse(LauncherPlan.nodeOk("garbage"));
	}

	@Test
	void npmCiKeepsDevDependencies() {
		List<String> c = LauncherPlan.installCommand(LauncherPlan.npmFor(Path.of("/opt/homebrew/bin/node"), false));
		assertEquals(List.of("/opt/homebrew/bin/npm", "ci", "--no-audit", "--no-fund"), c, "tsx is a dev dependency: no --omit=dev");
		assertTrue(LauncherPlan.depsReady(Path.of("/r"), p -> p.equals(Path.of("/r/foreman/node_modules/.package-lock.json"))));
		assertFalse(LauncherPlan.depsReady(Path.of("/r"), p -> false));
	}

	// ------------------------------------------------------------------ source

	private static final Set<Path> CHECKOUTS = Set.of(Path.of("/dev/repo/foreman/package.json"), Path.of("/dev/repo/foreman/src/main.ts"),
		Path.of("/Users/u/stable/foreman/package.json"), Path.of("/Users/u/stable/foreman/src/main.ts"), Path.of("/build/co/foreman/package.json"),
		Path.of("/build/co/foreman/src/main.ts"), Path.of("/cfg/co/foreman/package.json"), Path.of("/cfg/co/foreman/src/main.ts"));

	@Test
	void sourceOrder() {
		var all = LauncherPlan.sourceCandidates("/cfg/co", "/nope", Path.of("/dev/repo"), "~/stable", "/build/co");
		assertEquals(Path.of("/cfg/co"), LauncherPlan.source(all, "/Users/u", CHECKOUTS::contains).root(), "the override wins");
		var cfg = LauncherPlan.sourceCandidates(null, "/cfg/co/foreman", Path.of("/dev/repo"), "~/stable", "/build/co");
		LauncherPlan.Source s = LauncherPlan.source(cfg, "/Users/u", CHECKOUTS::contains);
		assertEquals(Path.of("/cfg/co"), s.root(), "launcher.foremanDir may name foreman/");
		assertEquals("launcher.foremanDir", s.origin());
		var dev = LauncherPlan.sourceCandidates(null, null, Path.of("/dev/repo"), "~/stable", "/build/co");
		assertEquals(Path.of("/dev/repo"), LauncherPlan.source(dev, "/Users/u", CHECKOUTS::contains).root(), "a dev run's checkout before stable");
		var jar = LauncherPlan.sourceCandidates(null, null, null, "~/stable", "/build/co");
		LauncherPlan.Source st = LauncherPlan.source(jar, "/Users/u", CHECKOUTS::contains);
		assertEquals(Path.of("/Users/u/stable"), st.root(), "hardcore.stable, ~ expanded");
		assertEquals("hardcore.stable", st.origin());
		var build = LauncherPlan.sourceCandidates(null, null, null, "/gone", "/build/co");
		assertEquals(Path.of("/build/co"), LauncherPlan.source(build, "/Users/u", CHECKOUTS::contains).root(), "a missing stable is skipped");
	}

	@Test
	void anExplicitBadSourceStops() {
		var bad = LauncherPlan.sourceCandidates(null, "/nope", Path.of("/dev/repo"), null, null);
		LauncherPlan.Source s = LauncherPlan.source(bad, "/Users/u", CHECKOUTS::contains);
		assertNull(s.root());
		assertTrue(s.problem().contains("launcher.foremanDir = /nope is not an AgentCraft checkout"));
		LauncherPlan.Source none = LauncherPlan.source(LauncherPlan.sourceCandidates(null, null, null, null, null), "/Users/u", CHECKOUTS::contains);
		assertNull(none.root());
		assertEquals("none", none.origin());
		assertNotNull(none.problem());
	}

	@Test
	void devCheckoutConflict() {
		List<Path> repos = List.of(Path.of("/code/agentcraft"), Path.of("/code/other"));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft"), null, repos));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft/.claude/worktrees/x"), null, repos));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/elsewhere/wt"), Path.of("/code/agentcraft/.git"), repos),
			"a worktree of a listed repo shares its common dir");
		assertNull(LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft-stable"), Path.of("/code/agentcraft-stable/.git"), repos),
			"a sibling folder with a shared prefix is not inside");
	}

	// ------------------------------------------------------------------ stale, ownership, the decision

	@Test
	void staleReasonsMatchTheDaemon() {
		assertTrue(LauncherPlan.staleReasons("/s", "abc123456789", "/s", "abc123456789").isEmpty());
		assertTrue(LauncherPlan.staleReasons("/s/../s", "abc", "/s", "abc").isEmpty(), "paths compared normalized");
		List<String> other = LauncherPlan.staleReasons("/dev", "abc", "/s", "abc");
		assertEquals(1, other.size());
		assertTrue(other.get(0).contains("different checkout (/dev)"));
		List<String> older = LauncherPlan.staleReasons("/s", "111111111aaaa", "/s", "222222222bbbb");
		assertTrue(older.get(0).contains("commit 111111111") && older.get(0).contains("222222222"));
		assertEquals(2, LauncherPlan.staleReasons("/dev", "1", "/s", "2").size());
		assertTrue(LauncherPlan.staleReasons("/s", "1", "/s", null).isEmpty(), "no git: the commit is not compared");
		assertEquals(1, LauncherPlan.staleReasons(null, null, "/s", "1").size(), "no record of where it came from: stale");
	}

	@Test
	void sameProcessNeedsAMatchingStartTime() {
		assertTrue(LauncherPlan.sameProcess(1_000_500, 1_000_000));
		assertTrue(LauncherPlan.sameProcess(999_000, 1_000_000), "clocks may disagree a little");
		assertFalse(LauncherPlan.sameProcess(2_000_000, 1_000_000), "a later process that reused the pid");
		assertFalse(LauncherPlan.sameProcess(-1, 1_000_000), "unknown start: never ours");
		assertFalse(LauncherPlan.sameProcess(1_000_000, 0));
	}

	@Test
	void ownership() {
		Running r = new Running(200, "p", "/s", "c", null);
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(0, 0, null, false, r, true), "no record");
		assertEquals(Owner.OURS, LauncherPlan.owner(200, 5, "/s", true, r, true));
		assertEquals(Owner.OURS, LauncherPlan.owner(200, 5, "/s", true, null, false), "ours, alive, no run file (hung)");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, "/s", true, r, true), "ours is alive but another holds the profile");
		assertEquals(Owner.OURS_RESTARTED, LauncherPlan.owner(100, 5, "/s", false, r, true), "hub restart: followed");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, "/s", false, r, false), "not a Foreman of the profile");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, "/s", false, new Running(200, "p", "/dev", "c", null), true), "another checkout");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, "/s", false, new Running(200, "p", null, null, null), true), "unknown checkout");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, "/s", false, null, false), "ours is gone, nothing runs");
	}

	@Test
	void decisions() {
		List<String> current = List.of();
		List<String> old = List.of("started at commit 1, the checkout is now at 2");
		assertEquals(Action.REUSE, LauncherPlan.decide(Probe.FOREMAN, false, current, false, false), "a current one, anyone's: reused");
		assertEquals(Action.REUSE, LauncherPlan.decide(Probe.FOREMAN, true, current, false, false));
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.FOREMAN, true, old, false, false), "ours and stale: restarted");
		assertEquals(Action.REUSE_OLDER, LauncherPlan.decide(Probe.FOREMAN, false, old, false, false), "not ours and stale: never killed");
		assertEquals(Action.REUSE_OLDER, LauncherPlan.decide(Probe.FOREMAN, false, old, false, true), "not even on Restart");
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.FOREMAN, true, current, false, true), "Restart of ours");
		assertEquals(Action.START, LauncherPlan.decide(Probe.CLOSED, false, current, false, false));
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.CLOSED, true, current, false, false), "ours alive but not listening (hung)");
		assertEquals(Action.PROFILE_BUSY, LauncherPlan.decide(Probe.CLOSED, false, current, true, false));
		assertEquals(Action.PORT_TAKEN, LauncherPlan.decide(Probe.NOT_FOREMAN, false, current, false, false), "not a Foreman, not ours: left alone");
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.NOT_FOREMAN, true, current, false, false), "ours, not answering");
	}

	// ------------------------------------------------------------------ command line and environment

	@Test
	void theDaemonsCommandLine() {
		List<String> args = LauncherPlan.foremanArgs("claude", "hardcore", "/Users/u/.agentcraft", 7880);
		assertEquals(List.of("--import", "tsx", "src/main.ts", "--backend", "claude", "--profile", "hardcore", "--home", "/Users/u/.agentcraft", "--port",
			"7880"), args);
		List<String> cmd = LauncherPlan.spawnCommand(Path.of("/n/node"), Path.of("/r/artifacts/logs/f.log"), Path.of("/r/foreman"), args);
		assertEquals("/n/node", cmd.get(0));
		assertEquals("-e", cmd.get(1));
		assertEquals(LauncherPlan.SPAWN_SCRIPT, cmd.get(2));
		assertEquals("/r/artifacts/logs/f.log", cmd.get(3));
		assertEquals("/r/foreman", cmd.get(4));
		assertEquals(args, cmd.subList(5, cmd.size()));
		assertTrue(LauncherPlan.SPAWN_SCRIPT.contains("detached:true"));
		assertEquals(4242, LauncherPlan.parsePid("4242"));
		assertEquals(-1, LauncherPlan.parsePid("Error: spawn ENOENT"));
		assertEquals(-1, LauncherPlan.parsePid(null));
		for (String a : cmd) {
			assertFalse(a.toLowerCase().contains("token") || a.toLowerCase().contains("key"), "no secrets on the command line: " + a);
		}
	}

	@Test
	void foremanCommandLines() {
		assertTrue(LauncherPlan.isForemanCommand("/n/node --import tsx src/main.ts --backend claude --profile hardcore --port 7880", "hardcore"));
		assertTrue(LauncherPlan.isForemanCommand("node /r/foreman/src/main.ts --profile=hardcore", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand("node src/main.ts --profile hardcore2", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand("vim src/main.ts", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand(null, "hardcore"));
	}

	@Test
	void loginEnvParsing() {
		String out = "Last login: today\nbanner " + LauncherPlan.ENV_MARK + " in text\n" + LauncherPlan.ENV_MARK + "\nPATH=/a:/b\0DOTNET_ROOT=/d\0X=1=2\0";
		Map<String, String> env = LauncherPlan.parseLoginEnv(out);
		assertEquals("/a:/b", env.get("PATH"));
		assertEquals("/d", env.get("DOTNET_ROOT"));
		assertEquals("1=2", env.get("X"));
		assertTrue(LauncherPlan.parseLoginEnv("no mark").isEmpty());
		assertTrue(LauncherPlan.parseLoginEnv(null).isEmpty());
	}

	@Test
	void environmentComposesPath() {
		Map<String, String> game = new HashMap<>(Map.of("PATH", "/usr/bin:/bin", "HOME", "/Users/u", "AGENTCRAFT_DEV_TOKEN", "secret",
			"AGENTCRAFT_PORT", "7878", "JAVA_HOME", "/j"));
		Map<String, String> login = Map.of("PATH", "/Users/u/.local/bin:/opt/homebrew/opt/dotnet@8/libexec:/usr/bin", "DOTNET_ROOT", "/d", "SHLVL",
			"2", "__MISE_DIFF", "x", "HOME", "/elsewhere", "AGENTCRAFT_LEAD_MODEL", "opus", "AGENTCRAFT_DEV_TOKEN", "t2", "AGENTCRAFT_CLIENT_TOKEN", "t3");
		Map<String, String> env = LauncherPlan.environment(game, login, Path.of("/Users/u/.local/share/mise/installs/node/22/bin/node"), "/Users/u", false);
		List<String> path = List.of(env.get("PATH").split(":"));
		assertEquals("/Users/u/.local/share/mise/installs/node/22/bin", path.get(0), "node's folder first");
		assertEquals("/Users/u/.local/bin", path.get(1), "then the login shell's PATH");
		assertTrue(path.indexOf("/opt/homebrew/opt/dotnet@8/libexec") < path.indexOf("/opt/homebrew/bin"));
		assertTrue(path.contains("/Users/u/.local/share/mise/shims") && path.contains("/usr/local/bin"), "the daemon's fallbacks");
		assertEquals(path.size(), path.stream().distinct().count(), "no duplicates");
		assertEquals("/d", env.get("DOTNET_ROOT"), "login variables the game lacks are added");
		assertEquals("/Users/u", env.get("HOME"), "the game's own variables win");
		assertEquals("/j", env.get("JAVA_HOME"));
		assertFalse(env.containsKey("SHLVL") || env.containsKey("__MISE_DIFF"), "shell bookkeeping dropped");
		assertFalse(env.containsKey("AGENTCRAFT_PORT"), "the game's AGENTCRAFT_* are not passed (flags and config.json decide)");
		assertEquals("opus", env.get("AGENTCRAFT_LEAD_MODEL"), "the user's own exports reach it, as with the daemon");
		assertFalse(env.containsKey("AGENTCRAFT_DEV_TOKEN") || env.containsKey("AGENTCRAFT_CLIENT_TOKEN"), "never the DevBridge or client token");
	}

	@Test
	void environmentWithoutALoginShell() {
		Map<String, String> env = LauncherPlan.environment(Map.of("PATH", "/usr/bin"), Map.of(), Path.of("/opt/homebrew/bin/node"), "/Users/u", false);
		assertTrue(env.get("PATH").startsWith("/opt/homebrew/bin:"));
		assertTrue(env.get("PATH").contains("/usr/bin"));
	}

	@Test
	void tailKeepsTheLastNonBlankLines() {
		assertEquals(List.of("b", "c"), LauncherPlan.tail(List.of("a", "", "b", " ", "c"), 2));
		assertEquals(List.of(), LauncherPlan.tail(List.of(), 5));
	}

	@Test
	void stateWireNames() {
		assertEquals("node-missing", LauncherPlan.State.NODE_MISSING.wire());
		assertEquals("running-older", LauncherPlan.State.RUNNING_OLDER.wire());
	}
}
