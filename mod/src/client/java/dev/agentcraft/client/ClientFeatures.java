package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.agents.AgentsFeature;
import dev.agentcraft.client.building.BuildingWizardFeature;
import dev.agentcraft.client.console.ConsoleFeature;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.design.DesignFeature;
import dev.agentcraft.client.diff.DiffFeature;
import dev.agentcraft.client.foreman.ForemanFeature;
import dev.agentcraft.client.hq.HqClientFeature;
import dev.agentcraft.client.hub.HubFeature;
import dev.agentcraft.client.hud.HudFeature;
import dev.agentcraft.client.launcher.Launcher;
import dev.agentcraft.client.leads.LeadsFeature;
import dev.agentcraft.client.library.LibraryFeature;
import dev.agentcraft.client.monitor.MonitorFeature;
import dev.agentcraft.client.permissions.PermissionsFeature;
import dev.agentcraft.client.road.RoadsFeature;
import dev.agentcraft.client.taskwall.TaskWallFeature;
import dev.agentcraft.client.trophy.TrophyFeature;
import dev.agentcraft.client.village.VillageBoardFeature;
import dev.agentcraft.client.ui.UiDev;
import dev.agentcraft.client.world.AnchorsDev;
import dev.agentcraft.client.world.ItemsDev;

/**
 * The one place that wires every client feature. Each feature lives in its own package with an
 * {@code init()} that registers everything it needs (renderers, HUD elements, screens, keybinds,
 * DevBridge commands, Foreman listeners), so a feature specialist only edits their own package.
 * Order matters only for {@link ForemanFeature} (first: everything else may read the state model).
 * See mod/FEATURES.md.
 */
public final class ClientFeatures {
	private ClientFeatures() {
	}

	public static void init() {
		ForemanFeature.init();   // link + state model (dev.foreman, dev.state.foreman)
		Launcher.init();         // starts the Foreman with the game (dev.launcher.*; docs/HUB.md "Foreman launcher")
		AnchorsDev.init();       // dev.anchors, dev.camera {anchor}
		UiDev.init();            // dev.state ui (pause, parent, crash guards), dev.ui.pause, dev.guard.inject
		ItemsDev.init();         // dev.screen creative_agentcraft
		AgentsFeature.init();    // agent NPCs, nameplates, dev.agents
		HudFeature.init();       // connection banner (+ Phase 3: goal boss bar, toasts)
		HqClientFeature.init();  // lamps / podium / atrium driven by state (Phase 3)
		MonitorFeature.init();
		TaskWallFeature.init();
		DecisionsFeature.init();
		ConsoleFeature.init();
		DiffFeature.init();
		LibraryFeature.init();
		PermissionsFeature.init();
		BuildingWizardFeature.init(); // /agentcraft build, B: pick repos + blueprint, place a ghost
		HubFeature.init();       // H, /hub: buildings, blueprints, status (docs/HUB.md)
		LeadsFeature.init();     // a lead per building: lead.assign/release/sync, dev.leads.state
		TrophyFeature.init();    // trophies for merges and finished goals, dev.trophies.*
		VillageBoardFeature.init(); // the village board fixture's display, right-click to the hub, dev.board.*
		RoadsFeature.init();     // roads between buildings: preview, lay, remove, dev.roads.* (docs/VILLAGE.md V1)
		DesignFeature.init();    // the hub's Design new…, /hub design, plot marking, design progress
		AgentCraft.LOGGER.info("AgentCraft client features initialised");
	}
}
