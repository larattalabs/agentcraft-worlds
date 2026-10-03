package dev.agentcraft.client.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.building.DesignSpec;
import dev.agentcraft.client.building.PlotMarker;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the design form holds (kept by {@link DesignFeature} across the form, plot marking and the
 * hub, so nothing typed is lost): the choices, the size (a preset, a marked plot or, from DevBridge, an
 * explicit limit), the remix, name and notes. Values are kept as entered, even invalid ones from
 * DevBridge, so {@link #errors} can show them inline. Client thread.
 */
public final class DesignForm {
	public static final String PLOT = "plot";
	public static final String CUSTOM = "custom";

	String kind = DesignSpec.SINGLE;
	/** The wing count for a group (kept while switching to single and back). */
	int groupWings = 2;
	String style = "modern";
	String materials = "agentcraft";
	final Set<String> features = new LinkedHashSet<>();
	/** S, M, L, plot or custom. */
	String size = "M";
	int @Nullable [] custom;
	DesignSpec.@Nullable Plot plot;
	@Nullable String remix;
	final TextModel name = new TextModel(DesignSpec.MAX_NAME);
	final TextModel notes = new TextModel(DesignSpec.MAX_NOTES);
	/** The last problem sending it (Foreman offline, ack refused), shown above the buttons. */
	@Nullable String sendError;
	/** Errors are shown once the player tried to submit (or a DevBridge submit), and then stay live. */
	boolean showErrors;

	public int wings() {
		return DesignSpec.GROUP.equals(kind) ? groupWings : 1;
	}

	public boolean group() {
		return DesignSpec.GROUP.equals(kind);
	}

	public DesignSpec.@Nullable Plot plot() {
		return plot;
	}

	/** The size limit {x, y, z} the request carries. */
	public int[] maxSize() {
		if (PLOT.equals(size) && plot != null) {
			return plot.maxSize();
		}
		if (CUSTOM.equals(size) && custom != null) {
			return custom.clone();
		}
		try {
			return DesignSpec.preset(size, kind, groupWings);
		} catch (IllegalArgumentException e) {
			return DesignSpec.preset("M", kind, groupWings);
		}
	}

	void setPlot(DesignSpec.Plot p) {
		plot = p;
		size = PLOT;
	}

	public DesignSpec.Draft draft(String outDir) {
		int[] m = maxSize();
		List<String> fs = new ArrayList<>(features);
		return new DesignSpec.Draft(kind, wings(), style, materials, fs, m[0], m[1], m[2], remix, name.value(), notes.value(), outDir);
	}

	/** Field -> problem (see {@link DesignSpec#validate}). */
	public Map<String, String> errors(String outDir) {
		return DesignSpec.validate(draft(outDir));
	}

	/** The {@code DesignRequest} on the wire: optional fields left out when blank (never null). */
	public JsonObject requestJson(String outDir) {
		int[] m = maxSize();
		JsonObject r = new JsonObject();
		r.addProperty("kind", kind);
		r.addProperty("wings", wings());
		r.addProperty("style", style);
		r.addProperty("materials", materials);
		JsonArray fs = new JsonArray();
		// protocol order, whatever order they were ticked in
		for (DesignSpec.Choice c : DesignSpec.FEATURES) {
			if (features.contains(c.id())) {
				fs.add(c.id());
			}
		}
		r.add("features", fs);
		JsonObject ms = new JsonObject();
		ms.addProperty("x", m[0]);
		ms.addProperty("y", m[1]);
		ms.addProperty("z", m[2]);
		r.add("maxSize", ms);
		if (!DesignSpec.blank(remix)) {
			r.addProperty("remix", remix);
		}
		if (!name.value().isBlank()) {
			r.addProperty("name", name.value().strip());
		}
		if (!notes.value().isBlank()) {
			r.addProperty("notes", notes.value().strip());
		}
		r.addProperty("outDir", outDir);
		return r;
	}

	// ------------------------------------------------------------------ DevBridge

	/**
	 * Sets the fields present in {@code f}: kind, wings, style, materials, features ([..] or "a,b"), size
	 * (S|M|L|plot), maxSize ({x,y,z} or [x,y,z]: an explicit limit), remix (null/""/"none" clears), name,
	 * notes. Unknown values are kept (and shown as errors); malformed JSON throws.
	 */
	void apply(Fields f) {
		JsonObject j = f.json();
		if (f.has("kind")) {
			kind = f.str("kind").strip().toLowerCase(Locale.ROOT);
		}
		if (f.has("wings")) {
			int w = f.optInt("wings", 2, -1000, 1000);
			if (!f.has("kind")) {
				kind = w == 1 ? DesignSpec.SINGLE : DesignSpec.GROUP;
			}
			if (group()) {
				groupWings = w;
			}
		}
		if (f.has("style")) {
			style = f.str("style").strip().toLowerCase(Locale.ROOT);
		}
		if (f.has("materials")) {
			materials = f.str("materials").strip().toLowerCase(Locale.ROOT);
		}
		if (j.has("features")) {
			features.clear();
			JsonElement el = j.get("features");
			if (el.isJsonArray()) {
				for (JsonElement x : el.getAsJsonArray()) {
					features.add(x.getAsString().strip());
				}
			} else if (!el.isJsonNull()) {
				for (String s : el.getAsString().split(",")) {
					if (!s.isBlank()) {
						features.add(s.strip());
					}
				}
			}
		}
		if (f.has("size")) {
			String s = f.str("size").strip();
			if (s.equalsIgnoreCase(PLOT)) {
				if (plot == null) {
					throw new DevBridge.DevException("size plot: no plot marked yet (dev.plot.start + dev.plot.corner twice)");
				}
				size = PLOT;
			} else if (DesignSpec.SIZES.contains(s.toUpperCase(Locale.ROOT))) {
				size = s.toUpperCase(Locale.ROOT);
			} else {
				throw new DevBridge.DevException("size must be S, M, L or plot");
			}
		}
		if (f.has("maxSize")) {
			JsonElement el = j.get("maxSize");
			int[] m;
			if (el.isJsonArray() && el.getAsJsonArray().size() == 3) {
				m = new int[] {el.getAsJsonArray().get(0).getAsInt(), el.getAsJsonArray().get(1).getAsInt(), el.getAsJsonArray().get(2).getAsInt()};
			} else if (el.isJsonObject()) {
				Fields ms = f.obj("maxSize");
				m = new int[] {(int) ms.integer("x", -10_000, 10_000), (int) ms.integer("y", -10_000, 10_000), (int) ms.integer("z", -10_000, 10_000)};
			} else {
				throw new DevBridge.DevException("maxSize must be {x, y, z} or [x, y, z]");
			}
			custom = m;
			size = CUSTOM;
		}
		if (j.has("remix")) {
			String r = j.get("remix").isJsonNull() ? null : j.get("remix").getAsString().strip();
			remix = r == null || r.isEmpty() || r.equalsIgnoreCase("none") ? null : r;
		}
		if (j.has("name")) {
			name.set(j.get("name").isJsonNull() ? "" : j.get("name").getAsString());
		}
		if (j.has("notes")) {
			notes.set(j.get("notes").isJsonNull() ? "" : j.get("notes").getAsString());
		}
		sendError = null;
	}

	JsonObject stateJson(String outDir) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind);
		o.addProperty("wings", wings());
		o.addProperty("style", style);
		o.addProperty("materials", materials);
		JsonArray fs = new JsonArray();
		features.forEach(fs::add);
		o.add("features", fs);
		o.addProperty("size", size);
		int[] m = maxSize();
		JsonObject ms = new JsonObject();
		ms.addProperty("x", m[0]);
		ms.addProperty("y", m[1]);
		ms.addProperty("z", m[2]);
		o.add("maxSize", ms);
		o.add("plot", plot == null ? null : PlotMarker.plotJson(plot));
		o.addProperty("remix", remix);
		o.addProperty("name", name.value());
		o.addProperty("notes", notes.value());
		o.addProperty("outDir", outDir);
		JsonObject errs = new JsonObject();
		errors(outDir).forEach(errs::addProperty);
		o.add("errors", errs);
		o.addProperty("errorsShown", showErrors);
		o.addProperty("sendError", sendError);
		o.add("request", requestJson(outDir));
		return o;
	}
}
