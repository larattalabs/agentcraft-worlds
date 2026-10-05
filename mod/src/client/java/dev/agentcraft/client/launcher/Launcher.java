package dev.agentcraft.client.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanFeature;
import dev.agentcraft.client.foreman.ForemanJson;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.launcher.LauncherConfig;
import dev.agentcraft.launcher.LauncherPlan;
import dev.agentcraft.launcher.LauncherPlan.Action;
import dev.agentcraft.launcher.LauncherPlan.Owner;
import dev.agentcraft.launcher.LauncherPlan.Probe;
import dev.agentcraft.launcher.LauncherPlan.Running;
import dev.agentcraft.launcher.LauncherPlan.State;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Starts the Foreman with the game, so a player needs neither Prism's PreLaunch hook nor tools/mac.mjs (docs/HUB.md
 * "Foreman launcher"). On client start, on its own worker thread (never the render thread):
 * <ol>
 *   <li>reads the {@code launcher} section of {@code <home>/config.json} ({@link LauncherConfig});</li>
 *   <li>picks the checkout to run from ({@link LauncherPlan#source}) and asks git for its commit;</li>
 *   <li>probes the port: {@code hello} with the client token, as the link does; an answer with a snapshot is a Foreman.
 *       Its identity (pid, profile, checkout, commit) comes from its run file {@code <home>/<profile>/foreman.json}, or
 *       the checkout's launcher run file;</li>
 *   <li>decides ({@link LauncherPlan#decide}): reuse a current one, leave one it did not start alone (never stopped, even
 *       when older: "running (older version)"), restart its own stale one, or start one;</li>
 *   <li>starting: finds node 22+, runs {@code npm ci} in foreman/ when node_modules is missing, and spawns the daemon's
 *       command line detached through node, with the login shell's environment and its output in
 *       {@code <checkout>/artifacts/logs/foreman-launcher-<profile>.log}. It records what it started (pid and start time)
 *       in {@code <home>/<profile>/launcher.json} and in the checkout's launcher run file (the one tools/mac.mjs and
 *       tools/foreman-daemon.mjs read), so the tools see it too.</li>
 * </ol>
 * The Foreman keeps running after the game exits (PR polling continues) unless {@code launcher.stopOnExit} is set, and
 * then only when this launcher started it.
 */
public final class Launcher {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "AgentCraft-Launcher");
		t.setDaemon(true);
		return t;
	});
	private static final long START_TIMEOUT_MS = 120_000;
	private static final long LOG_ROTATE_BYTES = 5L << 20;

	// settings (read again on every launch)
	private static volatile LauncherConfig config = LauncherConfig.DEFAULT;
	private static final int PORT = ClientEnv.intValue("AGENTCRAFT_PORT", 7878);
	private static final String BACKEND = backendSetting();
	private static final String PROFILE = profileSetting(BACKEND);

	// what the Status tab shows
	private static volatile State state = State.IDLE;
	private static volatile String detail = "";
	private static volatile List<String> logTail = List.of();
	private static volatile @Nullable String installLine;
	private static volatile LauncherPlan.@Nullable Source source;
	private static volatile @Nullable Path node;
	private static volatile @Nullable String nodeVersion;
	private static volatile @Nullable String expectedCommit;
	private static volatile long pid;
	private static volatile boolean ours;
	private static volatile boolean startedThisSession;
	private static volatile @Nullable String runningVersion;
	private static volatile @Nullable String runningRoot;
	private static volatile @Nullable String runningCommit;
	private static volatile @Nullable String runningBy;
	private static volatile List<String> stale = List.of();
	private static volatile @Nullable Action lastAction;
	private static volatile long changedAt = System.currentTimeMillis();
	private static volatile boolean stopping;
	private static volatile long watchGen;
	private static @Nullable Map<String, String> loginEnv;

	private Launcher() {
	}

	public static void init() {
		config = loadConfig();
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			if (config.enabled()) {
				start();
			} else {
				set(State.DISABLED, ClientEnv.raw("AGENTCRAFT_LAUNCHER") != null ? "off (AGENTCRAFT_LAUNCHER=0); the Start button starts one"
					: "off (launcher.enabled is false in config.json); the Start button starts one");
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> onGameExit());
		registerDev();
	}

	// ------------------------------------------------------------------ reads (any thread)

	public static State state() {
		return state;
	}

	public static String detail() {
		return detail;
	}

	public static List<String> logTail() {
		return logTail;
	}

	public static @Nullable String installLine() {
		return installLine;
	}

	public static long pid() {
		return pid;
	}

	/** Whether the running Foreman was started by this launcher (this session or an earlier one). */
	public static boolean ours() {
		return ours;
	}

	public static boolean startedThisSession() {
		return startedThisSession;
	}

	public static @Nullable String runningVersion() {
		return runningVersion;
	}

	public static @Nullable String runningRoot() {
		return runningRoot;
	}

	public static @Nullable String runningCommit() {
		return runningCommit;
	}

	public static List<String> stale() {
		return stale;
	}

	public static long changedAt() {
		return changedAt;
	}

	public static LauncherConfig config() {
		return config;
	}

	public static LauncherPlan.@Nullable Source source() {
		return source;
	}

	public static @Nullable Path node() {
		return node;
	}

	public static @Nullable String nodeVersion() {
		return nodeVersion;
	}

	/** The Foreman log of the current source ({@code <checkout>/artifacts/logs/foreman-launcher-<profile>.log}), or null. */
	public static @Nullable Path logFile() {
		LauncherPlan.Source s = source;
		return s == null || s.root() == null ? null : s.root().resolve("artifacts").resolve("logs").resolve("foreman-launcher-" + PROFILE + ".log");
	}

	private static @Nullable Path npmLog() {
		LauncherPlan.Source s = source;
		return s == null || s.root() == null ? null : s.root().resolve("artifacts").resolve("logs").resolve("foreman-launcher-npm.log");
	}

	/** Whether Restart / Stop apply (a live Foreman this launcher started). */
	public static boolean canRestart() {
		return ours && pid > 0 && (state == State.RUNNING || state == State.STARTING || state == State.CRASHED || state == State.RUNNING_OLDER);
	}

	/** Whether Start applies (nothing of ours or anyone's is running). */
	public static boolean canStart() {
		return switch (state) {
			case IDLE, DISABLED, NODE_MISSING, NO_SOURCE, CRASHED, STOPPED, BLOCKED -> true;
			default -> false;
		};
	}

	public static boolean busy() {
		return state == State.INSTALLING || state == State.STARTING;
	}

	// ------------------------------------------------------------------ control

	/** Runs the launch sequence on the worker thread (client start, Start). */
	public static void start() {
		WORKER.execute(() -> run(() -> launch(false)));
	}

	/** Restart: stops a Foreman this launcher started (never one it reused), then launches again. */
	public static void restart() {
		WORKER.execute(() -> run(() -> launch(true)));
	}

	/** Stops a Foreman this launcher started; anything else is left alone. */
	public static void stop() {
		WORKER.execute(() -> run(() -> {
			if (!ours || pid <= 0) {
				set(state, "nothing to stop: the running Foreman was not started by the game");
				return;
			}
			stopOurs("Stop", 8_000);
			set(State.STOPPED, "stopped (the Start button starts it again)");
		}));
	}

	private interface Step {
		void run() throws Exception;
	}

	private static void run(Step s) {
		try {
			s.run();
		} catch (Throwable t) {
			AgentCraft.LOGGER.error("Foreman launcher failed", t);
			set(State.CRASHED, "launcher error: " + t.getMessage());
		}
	}

	// ------------------------------------------------------------------ the sequence

	private static void launch(boolean forceRestart) throws Exception {
		stopping = false;
		config = loadConfig();
		for (String p : config.problems()) {
			AgentCraft.LOGGER.warn("Foreman launcher: config.json: {}", p);
		}
		String home = home().toString();
		LauncherPlan.Source src = LauncherPlan.source(LauncherPlan.sourceCandidates(config.foremanDirOverride(), config.foremanDir(), devRunCheckout(),
			config.stable(), buildCheckout()), System.getProperty("user.home", ""), Files::exists);
		source = src;
		Path root = src.root() == null ? null : realpath(src.root());
		if (root != null) {
			source = src = new LauncherPlan.Source(root, src.origin(), null);
		}
		AgentCraft.LOGGER.info("Foreman launcher: source {} ({}), profile {}, port {}, home {}", root, src.origin(), PROFILE, PORT, home);
		if (root == null) {
			// without a checkout we cannot start one, but a running Foreman is still worth reporting
			Probe p = probe();
			if (p == Probe.FOREMAN) {
				readIdentity(null);
				ours = false;
				pid = runningPid();
				set(State.RUNNING, "a Foreman is running on :" + PORT + " (" + src.problem() + ")");
			} else {
				set(State.NO_SOURCE, String.valueOf(src.problem()));
			}
			return;
		}
		if (!ClientEnv.DEV_RUN) {
			Path conflict = devConflict(root);
			if (conflict != null) {
				set(State.NO_SOURCE, "refusing to run the Foreman from " + root + ": it is (inside) " + conflict
					+ ", a repository the Foreman works on. Use the stable checkout (tools/hardcore-setup.mjs) or set launcher.foremanDir");
				return;
			}
		}
		expectedCommit = gitHead(root);

		// what runs on the port, and whose is it
		Probe probe = probe();
		Running running = readIdentity(root);
		JsonObject record = readJson(recordFile());
		long recordPid = record != null && record.has("pid") ? record.get("pid").getAsLong() : 0;
		long recordStart = record != null && record.has("startedAt") ? record.get("startedAt").getAsLong() : 0;
		String recordRoot = record != null && record.has("root") ? record.get("root").getAsString() : null;
		boolean recordAlive = recordPid > 0 && LauncherPlan.sameProcess(processStart(recordPid), recordStart);
		boolean runningIsForeman = running != null && LauncherPlan.isForemanCommand(commandLine(running.pid()), PROFILE);
		Owner owner = LauncherPlan.owner(recordPid, recordStart, recordRoot, recordAlive, running, runningIsForeman);
		if (owner == Owner.OURS_RESTARTED) {
			adopt(running, root);
		}
		ours = owner != Owner.FOREIGN;
		long ourPid = owner == Owner.OURS ? recordPid : owner == Owner.OURS_RESTARTED ? running.pid() : 0;
		boolean runningOnPort = running != null && probe == Probe.FOREMAN;
		List<String> reasons = runningOnPort ? LauncherPlan.staleReasons(running.root(), running.commit(), root.toString(), expectedCommit)
			: probe == Probe.FOREMAN ? List.of("its run file was not found (another home?)") : List.of();
		stale = reasons;
		Running busy = profileElsewhere(home);
		Action action = LauncherPlan.decide(probe, ours, reasons, busy != null, forceRestart);
		lastAction = action;
		AgentCraft.LOGGER.info("Foreman launcher: port {} {}, run file pid {}, ours {} ({}), stale {}, expected commit {}: {}", PORT, probe,
			running == null ? "-" : running.pid(), ours, owner, reasons, LauncherPlan.shortSha(expectedCommit), action);
		switch (action) {
			case REUSE -> {
				pid = running != null ? running.pid() : 0;
				set(State.RUNNING, ours ? oursDetail(startedThisSession ? "" : "started by the game earlier; ") : "reused: started by " + who() + "; "
					+ where());
				watch(pid);
				return;
			}
			case REUSE_OLDER -> {
				pid = running != null ? running.pid() : 0;
				set(State.RUNNING_OLDER, String.join("; ", reasons) + ". Started by " + who()
					+ ", so the game leaves it alone; restart it with the tool that started it");
				watch(pid);
				return;
			}
			case PORT_TAKEN -> {
				pid = 0;
				set(State.BLOCKED, "port " + PORT + " is in use by something that is not an AgentCraft Foreman (or does not answer); the game did not"
					+ " start it and leaves it alone. Free the port or set -Dagentcraft.port");
				return;
			}
			case PROFILE_BUSY -> {
				pid = busy.pid();
				set(State.BLOCKED, "profile \"" + PROFILE + "\" is already running on another port (pid " + busy.pid() + ", see " + foremanJson(home)
					+ "); the game connects to :" + PORT + ". Stop that Foreman or start the game with -Dagentcraft.port=<its port>");
				return;
			}
			case RESTART -> {
				set(State.STARTING, forceRestart ? "restarting the Foreman (pid " + ourPid + ")" : "restarting the Foreman (pid " + ourPid + "): "
					+ (reasons.isEmpty() ? "it does not answer" : String.join("; ", reasons)));
				pid = ourPid;
				stopOurs("restart", 8_000);
				stopping = false;
			}
			case START -> {
			}
		}
		spawn(root, home);
	}

	private static void spawn(Path root, String home) throws Exception {
		Path nodePath = findNode();
		if (nodePath == null) {
			set(State.NODE_MISSING, "Node.js " + LauncherPlan.MIN_NODE + " or newer was not found. Install it (" + LauncherPlan.NODE_INSTALL_URL
				+ ", or brew install node / mise use node@22), or set launcher.nodePath in " + configFile() + "; then press Start");
			return;
		}
		node = nodePath;
		Map<String, String> env = LauncherPlan.environment(System.getenv(), loginEnv(), nodePath, System.getProperty("user.home", ""), windows());
		if (!LauncherPlan.depsReady(root, Files::exists) && !install(root, nodePath, env)) {
			return;
		}
		Path log = logFile();
		Files.createDirectories(log.getParent());
		rotate(log);
		Files.writeString(log, "[agentcraft launcher " + Instant.now() + "] starting the Foreman: profile " + PROFILE + ", port " + PORT + ", home "
			+ home + ", backend " + BACKEND + ", commit " + LauncherPlan.shortSha(expectedCommit) + "\n", StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		List<String> args = LauncherPlan.foremanArgs(BACKEND, PROFILE, home, PORT);
		List<String> cmd = LauncherPlan.spawnCommand(nodePath, log, LauncherPlan.foremanDir(root), args);
		set(State.STARTING, "starting the Foreman (" + BACKEND + ", profile " + PROFILE + ")");
		long before = System.currentTimeMillis();
		String out = runCapture(cmd, root, env, 20_000);
		long newPid = LauncherPlan.parsePid(out);
		if (newPid <= 0) {
			logTail = readTail(log, 20);
			set(State.CRASHED, "could not start the Foreman: " + (out == null ? "the node helper failed" : out.strip()));
			return;
		}
		long started = processStart(newPid);
		pid = newPid;
		ours = true;
		startedThisSession = true;
		stale = List.of();
		runningRoot = root.toString();
		runningCommit = expectedCommit;
		runningBy = LauncherPlan.BY;
		writeRecords(newPid, started > 0 ? started : before, root, home, log, expectedCommit);
		AgentCraft.LOGGER.info("Foreman launcher: started the Foreman (pid {}) from {}; log {}", newPid, root, log);
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (!alive(newPid)) {
				if (probe() == Probe.FOREMAN) {
					// another launcher (Prism's daemon, tools/mac.mjs) won the race for the port: use its Foreman
					clearRecords(newPid);
					ours = false;
					startedThisSession = false;
					Running r = readIdentity(root);
					pid = r == null ? 0 : r.pid();
					stale = r == null ? List.of() : LauncherPlan.staleReasons(r.root(), r.commit(), root.toString(), expectedCommit);
					set(stale.isEmpty() ? State.RUNNING : State.RUNNING_OLDER, "another launcher started the Foreman first; reusing it ("
						+ who() + ")");
					watch(pid);
					return;
				}
				logTail = readTail(log, 20);
				set(State.CRASHED, "the Foreman exited during startup; last lines of " + log.getFileName());
				return;
			}
			if (portOpen()) {
				break;
			}
			Thread.sleep(400);
		}
		if (!portOpen()) {
			logTail = readTail(log, 20);
			set(State.CRASHED, "the Foreman did not open :" + PORT + " within " + START_TIMEOUT_MS / 1000 + " s");
			return;
		}
		probe();
		if (Foreman.link() != null) {
			Foreman.link().reconnectNow();
		}
		set(State.RUNNING, oursDetail(""));
		watch(newPid);
	}

	/** {@code npm ci} in foreman/ (first use), its output in artifacts/logs/foreman-launcher-npm.log. */
	private static boolean install(Path root, Path nodePath, Map<String, String> env) throws IOException, InterruptedException {
		Path dir = LauncherPlan.foremanDir(root);
		List<String> cmd = LauncherPlan.installCommand(LauncherPlan.npmFor(nodePath, windows()));
		set(State.INSTALLING, "installing the Foreman's npm packages (npm ci in " + dir + ", first use only)");
		Path log = npmLog();
		Files.createDirectories(log.getParent());
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().clear();
		pb.environment().putAll(env);
		Process p = pb.start();
		p.getOutputStream().close();
		List<String> lines = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				lines.add(line);
				if (!line.isBlank()) {
					installLine = line.strip();
				}
			}
		}
		int code = p.waitFor();
		List<String> logged = new ArrayList<>();
		logged.add("[agentcraft launcher " + Instant.now() + "] " + String.join(" ", cmd) + " in " + dir + " -> exit " + code);
		logged.addAll(lines);
		Files.write(log, logged, StandardCharsets.UTF_8);
		installLine = null;
		if (code != 0) {
			logTail = LauncherPlan.tail(lines, 20);
			set(State.CRASHED, "npm ci failed in " + dir + " (exit " + code + "); see " + log);
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ exits

	/** Follows the Foreman's pid: an exit is a crash (ours), a hub restart (followed), or the end of a reused one. */
	private static void watch(long p) {
		long gen = ++watchGen;
		ProcessHandle.of(p).ifPresent(h -> h.onExit().thenRun(() -> WORKER.execute(() -> run(() -> exited(p, gen)))));
	}

	private static void exited(long oldPid, long gen) throws Exception {
		if (gen != watchGen || stopping) {
			return;
		}
		// a hub restart (foreman.restart): the new process takes over the profile's run file within seconds
		String home = home().toString();
		for (int i = 0; i < 30 && gen == watchGen && !stopping; i++) {
			Running r = runFileOnPort(home);
			if (r != null && r.pid() != oldPid && alive(r.pid()) && LauncherPlan.isForemanCommand(commandLine(r.pid()), PROFILE)) {
				AgentCraft.LOGGER.info("Foreman launcher: pid {} exited, pid {} took over (restarted from the hub)", oldPid, r.pid());
				Path root = source == null ? null : source.root();
				if (ours && root != null && r.root() != null && LauncherPlan.staleReasons(r.root(), null, root.toString(), null).isEmpty()) {
					adopt(r, root);
				} else {
					ours = false;
				}
				pid = r.pid();
				runningRoot = r.root();
				runningCommit = r.commit();
				set(State.RUNNING, ours ? oursDetail("restarted from the hub; ") : "reused: started by " + who() + "; " + where());
				watch(r.pid());
				return;
			}
			Thread.sleep(500);
		}
		if (gen != watchGen || stopping) {
			return;
		}
		if (ours) {
			Path log = logFile();
			logTail = log == null ? List.of() : readTail(log, 20);
			set(State.CRASHED, "the Foreman (pid " + oldPid + ") exited; last lines of " + (log == null ? "its log" : log.getFileName()));
		} else {
			set(State.STOPPED, "the Foreman (pid " + oldPid + ", started by " + who() + ") exited; the Start button starts one");
		}
		pid = 0;
	}

	/** On client exit: stop the Foreman only with {@code launcher.stopOnExit} and only when this launcher started it. */
	private static void onGameExit() {
		if (!config.stopOnExit() || !ours || pid <= 0) {
			return;
		}
		stopOurs("game closing (launcher.stopOnExit)", 5_000);
	}

	/**
	 * Stops the Foreman our record names, after checking it is that process (pid and start time): SIGTERM (the Foreman
	 * saves its state and exits), then after {@code graceMs} SIGKILL to it and its children, except at game exit where it
	 * is left to finish (it force-exits itself after 8 s).
	 */
	private static void stopOurs(String why, long graceMs) {
		stopping = true;
		watchGen++;
		JsonObject record = readJson(recordFile());
		long p = pid;
		long recordStart = record != null && record.has("startedAt") ? record.get("startedAt").getAsLong() : 0;
		long recordPid = record != null && record.has("pid") ? record.get("pid").getAsLong() : 0;
		if (p <= 0 || recordPid != p || !LauncherPlan.sameProcess(processStart(p), recordStart)) {
			AgentCraft.LOGGER.warn("Foreman launcher: not stopping pid {} ({}): it is not the process our record names", p, why);
			return;
		}
		Optional<ProcessHandle> h = ProcessHandle.of(p);
		if (h.isEmpty() || !h.get().isAlive()) {
			clearRecords(p);
			return;
		}
		AgentCraft.LOGGER.info("Foreman launcher: stopping the Foreman we started (pid {}): {}", p, why);
		List<ProcessHandle> kids = h.get().descendants().toList();
		h.get().destroy();
		try {
			h.get().onExit().get(graceMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			if (!why.startsWith("game closing")) {
				h.get().destroyForcibly();
				kids.forEach(ProcessHandle::destroyForcibly);
			}
		}
		if (!h.get().isAlive()) {
			clearRecords(p);
			pid = 0;
		}
	}

	// ------------------------------------------------------------------ records

	/** Our own record: {@code <home>/<profile>/launcher.json} (kept per profile, so a change of checkout keeps it). */
	private static Path recordFile() {
		return home().resolve(PROFILE).resolve("launcher.json");
	}

	/** The checkout's launcher run file, shared with tools/mac.mjs and tools/foreman-daemon.mjs. */
	private static Path checkoutRunFile(Path root) {
		return root.resolve("artifacts").resolve("run").resolve("mac-foreman-" + PROFILE + ".json");
	}

	private static void writeRecords(long p, long startedAt, Path root, String home, Path log, @Nullable String commit) throws IOException {
		JsonObject r = new JsonObject();
		r.addProperty("pid", p);
		r.addProperty("startedAt", startedAt);
		r.addProperty("root", root.toString());
		r.addProperty("commit", commit);
		r.addProperty("port", PORT);
		r.addProperty("profile", PROFILE);
		r.addProperty("backend", BACKEND);
		r.addProperty("log", log.toString());
		r.addProperty("gamePid", ProcessHandle.current().pid());
		r.addProperty("by", LauncherPlan.BY);
		writeJson(recordFile(), r);
		// the tools' format (tools/foreman-daemon.mjs): stamp = `ps -o lstart=` (how they tell a reused pid)
		JsonObject t = new JsonObject();
		t.addProperty("pid", p);
		t.addProperty("stamp", psField(p, "lstart="));
		t.addProperty("log", log.toString());
		t.addProperty("startedAt", Instant.ofEpochMilli(startedAt).toString());
		t.addProperty("backend", BACKEND);
		t.addProperty("port", PORT);
		t.addProperty("home", home);
		t.addProperty("root", root.toString());
		t.addProperty("cwd", LauncherPlan.foremanDir(root).toString());
		t.addProperty("commit", commit);
		t.addProperty("script", "src/main.ts");
		t.addProperty("by", LauncherPlan.BY);
		writeJson(checkoutRunFile(root), t);
	}

	/** Follow ours after a hub restart: the records name the new process. */
	private static void adopt(Running r, Path root) {
		try {
			JsonObject rec = readJson(recordFile());
			Path log = logFile();
			long start = processStart(r.pid());
			// the restarted process ran the checkout's code as it was then: its run file says which commit
			String commit = r.commit() != null ? r.commit() : rec == null ? null : str(rec, "commit");
			writeRecords(r.pid(), start > 0 ? start : System.currentTimeMillis(), root, home().toString(), log == null ? root : log, commit);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not update the records", e);
		}
	}

	private static void clearRecords(long p) {
		try {
			Files.deleteIfExists(recordFile());
			Path root = source == null ? null : source.root();
			if (root != null) {
				JsonObject t = readJson(checkoutRunFile(root));
				if (t != null && t.has("pid") && t.get("pid").getAsLong() == p) {
					Files.deleteIfExists(checkoutRunFile(root));
				}
			}
		} catch (IOException ignored) {
			// best effort
		}
	}

	// ------------------------------------------------------------------ identity

	/**
	 * The Foreman on our port: the run file (profile's, home's, any profile's) whose port is ours and whose pid is alive;
	 * its checkout and commit from the run file (newer Foremen write them), else from the checkout's launcher run file
	 * when that names the same pid. Fills the running* fields. Null when none.
	 */
	private static @Nullable Running readIdentity(@Nullable Path root) {
		Running r = runFileOnPort(home().toString());
		runningRoot = null;
		runningCommit = null;
		runningBy = null;
		if (r == null) {
			return null;
		}
		String rootOf = r.root();
		String commit = r.commit();
		String by = null;
		JsonObject rec = readJson(recordFile());
		if (rec != null && rec.has("pid") && rec.get("pid").getAsLong() == r.pid()) {
			by = LauncherPlan.BY;
		}
		// the launcher run file of our checkout, or of the checkout the Foreman says it runs from (who started it)
		List<Path> roots = new ArrayList<>();
		if (root != null) {
			roots.add(root);
		}
		if (r.root() != null && (root == null || !Path.of(r.root()).equals(root))) {
			roots.add(Path.of(r.root()));
		}
		for (Path co : roots) {
			JsonObject t = readJson(checkoutRunFile(co));
			if (t != null && t.has("pid") && t.get("pid").getAsLong() == r.pid()) {
				rootOf = rootOf != null ? rootOf : str(t, "root");
				commit = commit != null ? commit : str(t, "commit");
				by = by != null ? by : str(t, "by") != null ? str(t, "by") : "tools/mac.mjs";
				break;
			}
		}
		runningRoot = rootOf;
		runningCommit = commit;
		runningBy = by;
		return new Running(r.pid(), r.profile(), rootOf, commit, by);
	}

	private static @Nullable Running runFileOnPort(String home) {
		List<Path> files = new ArrayList<>();
		Path h = Path.of(home);
		files.add(h.resolve(PROFILE).resolve("foreman.json"));
		files.add(h.resolve("foreman.json"));
		try (Stream<Path> s = Files.list(h)) {
			s.filter(Files::isDirectory).map(d -> d.resolve("foreman.json")).forEach(files::add);
		} catch (IOException ignored) {
			// no home yet
		}
		for (Path f : files) {
			JsonObject o = readJson(f);
			if (o == null || !o.has("pid") || !o.has("port") || o.get("port").getAsInt() != PORT) {
				continue;
			}
			long p = o.get("pid").getAsLong();
			if (alive(p)) {
				return new Running(p, str(o, "profile"), str(o, "root"), str(o, "commit"), null);
			}
		}
		return null;
	}

	/** The profile's Foreman when it runs on another port than ours (a second one would be refused), else null. */
	private static @Nullable Running profileElsewhere(String home) {
		JsonObject o = readJson(foremanJson(home));
		if (o == null || !o.has("pid") || !o.has("port") || o.get("port").getAsInt() == PORT) {
			return null;
		}
		long p = o.get("pid").getAsLong();
		return alive(p) && LauncherPlan.isForemanCommand(commandLine(p), PROFILE) ? new Running(p, PROFILE, str(o, "root"), str(o, "commit"), null) : null;
	}

	private static Path foremanJson(String home) {
		return Path.of(home).resolve(PROFILE).resolve("foreman.json");
	}

	private static long runningPid() {
		Running r = runFileOnPort(home().toString());
		return r == null ? 0 : r.pid();
	}

	private static String where() {
		return "profile " + PROFILE + " on :" + PORT;
	}

	private static String oursDetail(String prefix) {
		return prefix + where() + "; " + (config.stopOnExit() ? "stops when the game exits (launcher.stopOnExit)" : "keeps running after the game exits");
	}

	private static String who() {
		String by = runningBy;
		if (by == null) {
			return "something else (Prism's daemon, tools/mac.mjs or a terminal)";
		}
		return switch (by) {
			case LauncherPlan.BY -> "the game";
			case "foreman-daemon" -> "Prism's daemon (tools/foreman-daemon.sh)";
			default -> by;
		};
	}

	// ------------------------------------------------------------------ the port

	private static boolean portOpen() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * {@code hello} with the client token (as the link sends it): a Foreman answers with a {@code snapshot}; its
	 * {@code foreman.version} is noted. CLOSED when nothing listens, NOT_FOREMAN when something listens but no snapshot
	 * comes within 4 s.
	 */
	static Probe probe() {
		if (!portOpen()) {
			return Probe.CLOSED;
		}
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		CompletableFuture<JsonObject> snapshot = new CompletableFuture<>();
		WebSocket ws = null;
		try {
			ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2)).buildAsync(URI.create("ws://127.0.0.1:" + PORT), new WebSocket.Listener() {
				private final StringBuilder buf = new StringBuilder();

				@Override
				public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
					buf.append(data);
					if (last) {
						try {
							JsonObject o = JsonParser.parseString(buf.toString()).getAsJsonObject();
							if (o.has("type") && "snapshot".equals(o.get("type").getAsString())) {
								snapshot.complete(o);
							}
						} catch (RuntimeException ignored) {
							// not ours
						}
						buf.setLength(0);
					}
					w.request(1);
					return null;
				}
			}).get(3, TimeUnit.SECONDS);
			dev.agentcraft.hub.ClientToken.Found tok = ForemanFeature.clientToken(PORT);
			JsonObject hello = ForemanJson.msg("hello").put("modVersion", modVersion()).put("protocol", Protocol.VERSION).put("client", "mod")
				.put("token", tok.token()).json();
			ws.sendText(hello.toString(), true);
			JsonObject snap = snapshot.get(4, TimeUnit.SECONDS);
			JsonObject st = snap.has("foreman") && snap.get("foreman").isJsonObject() ? snap.getAsJsonObject("foreman") : null;
			runningVersion = st != null && st.has("version") ? st.get("version").getAsString() : null;
			return Probe.FOREMAN;
		} catch (Exception e) {
			return Probe.NOT_FOREMAN;
		} finally {
			if (ws != null) {
				ws.abort();
			}
		}
	}

	// ------------------------------------------------------------------ node and the shell

	private static @Nullable Path findNode() {
		LauncherPlan.Host host = host();
		for (Path p : LauncherPlan.nodeCandidates(config.nodePath(), host)) {
			if (host.executable().test(p) && checkNode(p)) {
				return p;
			}
		}
		String out = runCapture(LauncherPlan.shellProbe(host), null, null, 8000);
		Path probed = out == null ? null : LauncherPlan.parseProbe(out);
		if (probed != null && Files.isExecutable(probed) && checkNode(probed)) {
			return probed;
		}
		return null;
	}

	private static LauncherPlan.Host host() {
		return new LauncherPlan.Host(System.getenv(), System.getProperty("os.name", ""), System.getProperty("user.home", ""),
			p -> Files.isRegularFile(p) && Files.isExecutable(p), Launcher::list);
	}

	private static boolean checkNode(Path p) {
		String v = runCapture(List.of(p.toString(), "--version"), null, null, 8000);
		if (LauncherPlan.nodeOk(v)) {
			nodeVersion = v == null ? null : v.strip();
			return true;
		}
		AgentCraft.LOGGER.info("Foreman launcher: {} is not node {}+ ({})", p, LauncherPlan.MIN_NODE, v == null ? "did not run" : v.strip());
		return false;
	}

	/** The login shell's environment (computed once; empty when the shell does not answer). Never logged. */
	private static synchronized Map<String, String> loginEnv() {
		if (loginEnv == null) {
			Map<String, String> env = Map.of();
			for (List<String> probe : LauncherPlan.loginEnvProbes(host())) {
				env = LauncherPlan.parseLoginEnv(runCapture(probe, null, null, 10_000));
				if (!env.isEmpty()) {
					AgentCraft.LOGGER.info("Foreman launcher: login environment from {} {} ({} variables)", probe.get(0), probe.get(1), env.size());
					break;
				}
			}
			loginEnv = env;
		}
		return loginEnv;
	}

	private static @Nullable String gitHead(Path root) {
		String out = runCapture(List.of("git", "rev-parse", "HEAD"), root, null, 8000);
		return out == null || !out.strip().matches("[0-9a-f]{7,64}") ? null : out.strip();
	}

	/** The repository from config.json {@code repos} this checkout is (inside), or null (daemonplan.mjs). */
	private static @Nullable Path devConflict(Path root) {
		List<Path> repos = new ArrayList<>();
		for (String r : config.repos()) {
			repos.add(realpath(Path.of(LauncherPlan.expandHome(r, System.getProperty("user.home", "")))));
		}
		String common = runCapture(List.of("git", "rev-parse", "--path-format=absolute", "--git-common-dir"), root, null, 8000);
		Path commonDir = common == null || common.isBlank() ? null : realpath(Path.of(common.strip()));
		return LauncherPlan.devCheckoutConflict(root, commonDir, repos);
	}

	/** Runs a short command (stdin closed), returning its output (stdout and stderr), or null when it failed or timed out. */
	private static @Nullable String runCapture(List<String> cmd, @Nullable Path dir, @Nullable Map<String, String> env, long timeoutMs) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
			if (dir != null) {
				pb.directory(dir.toFile());
			}
			if (env != null) {
				pb.environment().clear();
				pb.environment().putAll(env);
			}
			Process p = pb.start();
			p.getOutputStream().close();
			CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
				try (InputStream in = p.getInputStream()) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					return "";
				}
			});
			if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() == 0 ? out.get(2, TimeUnit.SECONDS) : null;
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ processes

	private static boolean alive(long p) {
		return p > 0 && ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false);
	}

	/** The process's start time (ms), or -1 when unknown. */
	private static long processStart(long p) {
		return ProcessHandle.of(p).filter(ProcessHandle::isAlive).flatMap(h -> h.info().startInstant()).map(Instant::toEpochMilli).orElse(-1L);
	}

	private static @Nullable String commandLine(long p) {
		if (!windows()) {
			String ps = psField(p, "command=");
			if (ps != null) {
				return ps;
			}
		}
		return ProcessHandle.of(p).flatMap(h -> h.info().commandLine()).orElse(null);
	}

	/** {@code ps -p PID -o FIELD} trimmed, or null (Windows, or no such process). */
	private static @Nullable String psField(long p, String field) {
		if (windows()) {
			return null;
		}
		String out = runCapture(List.of("ps", "-p", Long.toString(p), "-o", field), null, null, 5000);
		return out == null || out.isBlank() ? null : out.strip();
	}

	// ------------------------------------------------------------------ settings

	/** The Foreman's home: {@code AGENTCRAFT_HOME} (also {@code -Dagentcraft.home}), else {@code ~/.agentcraft}. */
	public static Path home() {
		String h = ClientEnv.raw("AGENTCRAFT_HOME");
		String user = System.getProperty("user.home", "");
		return Path.of(h != null ? LauncherPlan.expandHome(h, user) : Path.of(user, ".agentcraft").toString()).toAbsolutePath().normalize();
	}

	public static Path configFile() {
		return home().resolve("config.json");
	}

	public static String profile() {
		return PROFILE;
	}

	public static int port() {
		return PORT;
	}

	public static String backend() {
		return BACKEND;
	}

	private static String backendSetting() {
		String b = ClientEnv.raw("AGENTCRAFT_BACKEND");
		return b != null && (b.equals("sim") || b.equals("claude")) ? b : "claude";
	}

	/** {@code AGENTCRAFT_PROFILE}, else the backend's name (the Foreman's own default). */
	private static String profileSetting(String backend) {
		String p = ClientEnv.raw("AGENTCRAFT_PROFILE");
		return p != null && p.matches("[A-Za-z0-9_-]+") ? p : backend;
	}

	private static LauncherConfig loadConfig() {
		String text = null;
		try {
			Path f = configFile();
			if (Files.exists(f)) {
				text = Files.readString(f, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not read {}", configFile(), e);
		}
		return LauncherConfig.parse(text, ClientEnv::raw);
	}

	/** A dev run's checkout: the game dir is {@code <repo>/mod/run}. */
	private static @Nullable Path devRunCheckout() {
		if (!ClientEnv.DEV_RUN) {
			return null;
		}
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path mod = game.getParent();
		return mod == null || mod.getParent() == null ? null : mod.getParent();
	}

	/** The checkout the jar was built from ({@code agentcraft-build.properties}, written by mod/build.gradle), or null. */
	private static @Nullable String buildCheckout() {
		try (InputStream in = Launcher.class.getClassLoader().getResourceAsStream("agentcraft-build.properties")) {
			if (in == null) {
				return null;
			}
			Properties p = new Properties();
			p.load(in);
			return p.getProperty("checkout");
		} catch (IOException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ files

	private static Path realpath(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return p.toAbsolutePath().normalize();
		}
	}

	private static List<Path> list(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static @Nullable JsonObject readJson(@Nullable Path f) {
		if (f == null) {
			return null;
		}
		try {
			return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	private static void writeJson(Path f, JsonObject o) throws IOException {
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static @Nullable String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	/** Like tools/lib/logrotate.mjs: at 5 MB the log moves to .1 (.1 to .2, .2 to .3). */
	private static void rotate(Path log) {
		try {
			if (!Files.exists(log) || Files.size(log) < LOG_ROTATE_BYTES) {
				return;
			}
			for (int i = 2; i >= 1; i--) {
				Path from = log.resolveSibling(log.getFileName() + "." + i);
				if (Files.exists(from)) {
					Files.move(from, log.resolveSibling(log.getFileName() + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not rotate {}", log, e);
		}
	}

	static List<String> readTail(Path log, int n) {
		try {
			return LauncherPlan.tail(Files.readAllLines(log, StandardCharsets.UTF_8), n);
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Opens the log in the OS's viewer (Status tab "Open log"). */
	public static void openLog() {
		Path log = logFile();
		if (log == null || !Files.exists(log)) {
			return;
		}
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> cmd = os.contains("mac") ? List.of("open", "-t", log.toString()) : windows() ? List.of("explorer", log.toString())
			: List.of("xdg-open", log.toString());
		try {
			new ProcessBuilder(cmd).start();
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not open {}", log, e);
		}
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	private static boolean windows() {
		return LauncherPlan.Host.windows(System.getProperty("os.name", ""));
	}

	// ------------------------------------------------------------------ state

	private static void set(State s, String why) {
		State before = state;
		state = s;
		detail = why;
		changedAt = System.currentTimeMillis();
		if (s == State.RUNNING || s == State.RUNNING_OLDER || s == State.STARTING) {
			logTail = s == State.STARTING ? logTail : List.of();
		}
		AgentCraft.LOGGER.info("Foreman launcher: {} ({})", s.wire(), why);
		if (before == s) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		mc.execute(() -> {
			String key = Keys.hub == null ? "H" : Keys.label(Keys.hub);
			switch (s) {
				case CRASHED -> toast(Protocol.NotifyLevel.WARN, "The Foreman stopped: " + why, key);
				case NODE_MISSING -> toast(Protocol.NotifyLevel.NEED_USER, "The Foreman needs Node.js " + LauncherPlan.MIN_NODE
					+ "+: see the hub's Status tab", key);
				case BLOCKED -> toast(Protocol.NotifyLevel.WARN, "The Foreman could not start: see the hub's Status tab", key);
				default -> {
				}
			}
		});
	}

	private static void toast(Protocol.NotifyLevel level, String text, String key) {
		Toasts.push(new Protocol.Notify(level, text, null, System.currentTimeMillis()), key, "status");
	}

	/** For the DevBridge ({@code dev.launcher.state}) and the Status tab. */
	public static JsonObject json() {
		JsonObject o = new JsonObject();
		o.addProperty("state", state.wire());
		o.addProperty("detail", detail);
		o.addProperty("changedAgoMs", System.currentTimeMillis() - changedAt);
		o.addProperty("installLine", installLine);
		o.addProperty("action", lastAction == null ? null : lastAction.name().toLowerCase(Locale.ROOT));
		o.addProperty("pid", pid);
		o.addProperty("ours", ours);
		o.addProperty("startedThisSession", startedThisSession);
		o.addProperty("runningBy", runningBy);
		o.addProperty("runningRoot", runningRoot);
		o.addProperty("runningCommit", runningCommit);
		o.addProperty("runningVersion", runningVersion);
		JsonArray st = new JsonArray();
		stale.forEach(st::add);
		o.add("stale", st);
		LauncherPlan.Source s = source;
		o.addProperty("source", s == null || s.root() == null ? null : s.root().toString());
		o.addProperty("sourceOrigin", s == null ? null : s.origin());
		o.addProperty("expectedCommit", expectedCommit);
		o.addProperty("node", node == null ? null : node.toString());
		o.addProperty("nodeVersion", nodeVersion);
		o.addProperty("profile", PROFILE);
		o.addProperty("port", PORT);
		o.addProperty("backend", BACKEND);
		o.addProperty("home", home().toString());
		Path log = logFile();
		o.addProperty("log", log == null ? null : log.toString());
		o.addProperty("enabled", config.enabled());
		o.addProperty("stopOnExit", config.stopOnExit());
		JsonArray problems = new JsonArray();
		config.problems().forEach(problems::add);
		o.add("configProblems", problems);
		JsonArray tail = new JsonArray();
		logTail.forEach(tail::add);
		o.add("logTail", tail);
		return o;
	}

	private static void registerDev() {
		DevBridge.register("dev.launcher.state", 5_000, "{} - the Foreman launcher: state (disabled|node-missing|no-source|installing|starting|running|"
			+ "running-older|crashed|blocked|stopped), detail, pid, ours, source, node, log, logTail",
			(req, mc) -> CompletableFuture.completedFuture(json()));
		DevBridge.register("dev.launcher.start", 5_000, "{} - run the launch sequence (reuse, start, or report); poll dev.launcher.state",
			(req, mc) -> {
				start();
				return CompletableFuture.completedFuture(json());
			});
		DevBridge.register("dev.launcher.restart", 5_000, "{} - restart the Foreman the game started (refused for one it did not start)",
			(req, mc) -> {
				if (!ours) {
					throw new DevBridge.DevException("the running Foreman was not started by the game; it is never restarted from here");
				}
				restart();
				return CompletableFuture.completedFuture(json());
			});
		DevBridge.register("dev.launcher.stop", 15_000, "{} - stop the Foreman the game started (refused for one it did not start)",
			(req, mc) -> {
				if (!ours || pid <= 0) {
					throw new DevBridge.DevException("the running Foreman was not started by the game; it is never stopped from here");
				}
				stop();
				return CompletableFuture.completedFuture(json());
			});
	}
}
