package dev.agentcraft.client.foreman;

import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.foreman.Protocol.DecisionStatus;
import dev.agentcraft.client.foreman.Protocol.DesignStatus;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written companion of the GENERATED {@link Protocol} (foreman/scripts/gen-java-protocol.ts): behaviour on top of
 * the protocol records and enums, as interfaces with default methods that the generated types implement (the
 * generator's {@code JAVA.mixins} table), so call sites keep writing {@code goal.allRepos()} or {@code state.family()}.
 * Also the ack results that have no zod schema. Keep data shapes out of here: they belong in foreman/src/protocol.ts.
 */
public final class ProtocolSupport {
	private ProtocolSupport() {
	}

	/**
	 * The agent name to show: the Foreman's, unless it is missing or just the id (a new lead without a cast entry yet):
	 * then the cast's name, else the id capitalised ("ines" -> "Ines"). Applied by {@link Protocol.Agent}'s constructor.
	 */
	static @Nullable String displayName(@Nullable String id, @Nullable String name) {
		if (id == null || id.isEmpty() || name != null && !name.isBlank() && !name.equals(id)) {
			return name == null ? id : name;
		}
		dev.agentcraft.Cast.Member m = dev.agentcraft.Cast.get(id);
		return m != null ? m.name() : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	// ------------------------------------------------------------------ mixins (implemented by generated types)

	public interface AgentStateHelpers {
		/** Status-dot / lamp family: idle, thinking, working, waiting, error, done. */
		default String family() {
			return switch ((AgentState) this) {
				case THINKING -> "thinking";
				case READING, EDITING, RUNNING, TESTING -> "working";
				case WAITING_USER -> "waiting";
				case BLOCKED, ERROR -> "error";
				case DONE -> "done";
				default -> "idle";
			};
		}
	}

	public interface DesignStatusHelpers {
		/** done, failed and cancelled are final. */
		default boolean isFinal() {
			DesignStatus s = (DesignStatus) this;
			return s == DesignStatus.DONE || s == DesignStatus.FAILED || s == DesignStatus.CANCELLED;
		}

		/** Still queued or being worked on (cancellable). */
		default boolean isRunning() {
			DesignStatus s = (DesignStatus) this;
			return s == DesignStatus.QUEUED || s == DesignStatus.DESIGNING || s == DesignStatus.CHECKING || s == DesignStatus.RENDERING;
		}
	}

	public interface AgentHelpers {
		@Nullable Boolean paused();

		@Nullable Boolean active();

		/** false = off shift (render idle in the lounge). Missing = true. */
		default boolean isActive() {
			Boolean a = active();
			return a == null || a;
		}

		default boolean isPaused() {
			Boolean p = paused();
			return p != null && p;
		}
	}

	public interface TaskPrHelpers {
		/** open|changes|approved|merged|abandoned, kept as the wire string (display only). */
		String status();

		/** Still on the host (open, changes, approved). */
		default boolean isOpen() {
			return !status().equals("merged") && !status().equals("abandoned");
		}
	}

	public interface DecisionHelpers {
		DecisionStatus status();

		List<String> options();

		@Nullable Boolean textAllowed();

		default boolean isOpen() {
			return status() == DecisionStatus.OPEN;
		}

		/**
		 * Whether a free-text answer is accepted (contract C1, docs/FIXWAVE.md): unless the Foreman said no
		 * ({@code textAllowed} false, absent on an older Foreman = true); always when there are no options to pick.
		 */
		default boolean freeText() {
			Boolean t = textAllowed();
			return t == null || t || options().isEmpty();
		}
	}

	public interface GoalHelpers {
		@Nullable String repoId();

		@Nullable List<String> repos();

		@Nullable String leadId();

		GoalStatus status();

		/** Every repo the goal touches: {@code repos} when the Foreman sends it, else just {@code repoId}. */
		default List<String> allRepos() {
			List<String> rs = repos();
			if (rs != null && !rs.isEmpty()) {
				return rs;
			}
			String r = repoId();
			return r == null ? List.of() : List.of(r);
		}

		/** Whether the goal is still being planned or worked on. */
		default boolean isOpen() {
			return status() == GoalStatus.PLANNING || status() == GoalStatus.ACTIVE;
		}

		/** The goal's lead: {@code leadId}, else marlow. */
		default String lead() {
			String l = leadId();
			return l == null || l.isBlank() ? "marlow" : l;
		}
	}

	// ------------------------------------------------------------------ ack results without a zod schema

	/** The {@code agent.logs.request} ack's result (W3, docs/WAVE2.md): a page of the stored log, oldest first; {@code more} = older ones exist. */
	public record LogPage(String agentId, List<LogEntry> entries, boolean more) {
		public LogPage {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	/**
	 * One of a repo's {@code .claude/agents} files ({@code repo.agents} ack), for the roles picker: {@code id} = the
	 * file name without .md (the value {@code roles.<agent>} stores), {@code name} = its front matter name.
	 */
	public record RepoAgentFile(String id, String name, @Nullable String path, @Nullable String description, @Nullable String model) {
		public RepoAgentFile {
			name = name == null ? id == null ? "" : id : name;
			id = id == null || id.isBlank() ? name : id;
		}
	}
}
