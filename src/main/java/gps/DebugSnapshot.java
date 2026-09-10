package gps;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.RuneLite;

/**
 * A JSON snapshot of the current routing state, written to {@code ~/.runelite/gps-debug/}:
 * everything needed to reproduce and debug the current path (routes with their full tile paths,
 * methods and edge data, mode and exclusions, player position, GPS progress state, the relevant
 * config, every non-zero varbit, and the last generation's timing). Triggered by the panel's
 * "Save debug snapshot" item; confirms via a game message. Built on the client thread.
 */
@Slf4j
final class DebugSnapshot
{
	private final ShortestPathPlugin plugin;

	DebugSnapshot(ShortestPathPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Builds and writes the snapshot, CLIENT THREAD. Failures are logged, never thrown. */
	void capture()
	{
		try
		{
			File out = write(build());
			log.info("GPS debug snapshot saved to {}", out.getAbsolutePath());
			Client client = plugin.getClient();
			if (GameState.LOGGED_IN.equals(client.getGameState()))
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"GPS debug snapshot saved to " + out.getAbsolutePath(), null);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to capture GPS debug snapshot", e);
		}
	}

	private Map<String, Object> build()
	{
		Client client = plugin.getClient();
		RouteSession session = plugin.session();
		ShortestPathConfig config = plugin.getGpsConfig();
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("capturedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
		snapshot.put("pluginVersion", BuildInfo.pluginVersion());
		snapshot.put("buildCommit", BuildInfo.buildCommit());
		snapshot.put("varbitSnapshot", varbitSnapshot(client));
		Player local = client.getLocalPlayer();
		int playerPacked = local != null
			? WorldPointUtil.fromLocalInstance(client, local) : WorldPointUtil.UNDEFINED;
		snapshot.put("player", packedPointJson(playerPacked));
		snapshot.put("routesMode", String.valueOf(plugin.getRoutesMode()));
		snapshot.put("routeLimit", session.limit());
		snapshot.put("routeCostMultiple", session.costMultiple());
		snapshot.put("targetSource", plugin.getTargetSource());
		snapshot.put("altStart", packedPointJson(session.lastStart()));
		List<Object> targets = new ArrayList<>();
		for (int target : session.lastTargets())
		{
			targets.add(packedPointJson(target));
		}
		snapshot.put("targets", targets);
		List<String> exclusions = new ArrayList<>();
		for (TeleportMethod method : plugin.getUserExclusions())
		{
			exclusions.add(methodKey(method));
		}
		snapshot.put("userExclusions", exclusions);
		snapshot.put("bankContentsKnown", plugin.isBankContentsKnown());
		snapshot.put("bankRestored", plugin.isBankRestored());
		// Smart-detection state, for diagnosing "GPS didn't notice my house/trees" reports.
		snapshot.put("pohSceneLoaded", PlayerOwnedHouse.isHouseScene(client.getTopLevelWorldView()));
		snapshot.put("pohScanned", plugin.isPohScanned());
		snapshot.put("pohDetectedFurniture", PohScanner.encode(plugin.pohDetection().detected()));
		snapshot.put("spiritTreesSynced", plugin.isSpiritTreeSynced());
		snapshot.put("spiritTreesParsedLive", plugin.spiritTreesParsedLive());

		// includeBankPath and useTeleportationItems are omitted: the Owned/All mode forces them
		// (see PathfinderConfig.refresh), so their config value is overridden and misleading;
		// routesMode above is the effective control.
		Map<String, Object> configValues = new LinkedHashMap<>();
		configValues.put("avoidWilderness", ShortestPathPlugin.override("avoidWilderness", config.avoidWilderness()));
		configValues.put("costBankPickup", ShortestPathPlugin.override("costBankPickup", config.costBankPickup()));
		configValues.put("defaultRouteCount", ShortestPathPlugin.override("defaultRouteCount", config.defaultRouteCount()));
		snapshot.put("config", configValues);

		RouteOption displayed = plugin.getDisplayedRoute();
		List<RouteOption> routes = session.routes();
		snapshot.put("displayedRouteIndex", displayed != null ? routes.indexOf(displayed) : -1);
		List<Object> routesJson = new ArrayList<>();
		for (RouteOption route : routes)
		{
			routesJson.add(routeJson(route));
		}
		snapshot.put("routes", routesJson);
		Map<String, Object> timing = generationTiming(plugin.altRoutesService());
		if (timing != null)
		{
			snapshot.put("altGenTiming", timing);
		}
		if (displayed != null)
		{
			snapshot.put("directions", stepsJson(plugin.getRouteDirections(displayed)));
			RouteDirectionsOverlay overlay = plugin.routeDirectionsOverlay();
			Map<String, Object> progress = new LinkedHashMap<>();
			progress.put("reachedIndex", overlay.getReachedIndex());
			progress.put("liveRemainingTicks", overlay.getLiveRemainingTicks());
			progress.put("speedTilesPerSecond", overlay.getSpeedTilesPerSecond());
			snapshot.put("progress", progress);
		}
		return snapshot;
	}

	/**
	 * Every non-zero varbit, for identifying state-dependent transport gates (mushtree discovery,
	 * balloon route unlocks): capture before and after the in-game action and diff the two files;
	 * the flipped id is the gate. A few thousand entries, debug-file-sized only.
	 */
	private static Map<String, Integer> varbitSnapshot(Client client)
	{
		Map<String, Integer> varbits = new LinkedHashMap<>();
		for (int id = 0; id <= 20000; id++)
		{
			try
			{
				int value = client.getVarbitValue(id);
				if (value != 0)
				{
					varbits.put(Integer.toString(id), value);
				}
			}
			catch (Exception ignored)
			{
				// Unknown varbit ids past the cache's definitions: skip.
			}
		}
		return varbits;
	}

	private Map<String, Object> routeJson(RouteOption route)
	{
		Map<String, Object> routeJson = new LinkedHashMap<>();
		routeJson.put("totalCost", route.getTotalCost());
		routeJson.put("rawCost", route.getRawCost());
		routeJson.put("reached", route.isReached());
		routeJson.put("viaBank", route.isViaBank());
		List<String> methods = new ArrayList<>();
		for (TeleportMethod method : route.getMethods())
		{
			methods.add(methodKey(method));
		}
		routeJson.put("methods", methods);
		routeJson.put("methodEdgeIndexes", route.getMethodEdgeIndexes());
		routeJson.put("methodDurations", route.getMethodDurations());
		routeJson.put("walkBeforeSteps", route.getWalkBeforeSteps());
		routeJson.put("trailingWalkSteps", route.getTrailingWalkSteps());
		List<Integer> packedPath = new ArrayList<>(route.getPath().size());
		List<Integer> bankFlips = new ArrayList<>();
		for (int i = 0; i < route.getPath().size(); i++)
		{
			packedPath.add(route.getPath().get(i).getPackedPosition());
			if (route.getPath().get(i).isBankVisited()
				&& (i == 0 || !route.getPath().get(i - 1).isBankVisited()))
			{
				bankFlips.add(i);
			}
		}
		routeJson.put("packedPath", packedPath);
		routeJson.put("bankVisitedFrom", bankFlips);
		// Fresh directions build per route, timed: the dashboard renders the step list for every
		// route and charts how long step derivation takes.
		long buildStart = System.nanoTime();
		List<RouteDirections.Step> routeSteps = RouteDirections.build(plugin, route);
		routeJson.put("directionsBuildMicros", (System.nanoTime() - buildStart) / 1_000);
		routeJson.put("directions", stepsJson(routeSteps));
		return routeJson;
	}

	/** The last generation's timing and per-search profiles (slowest first), or null when none ran. */
	private static Map<String, Object> generationTiming(AlternativeRoutesService service)
	{
		long[] genTiming = service != null ? service.getLastTimingSummary() : null;
		if (genTiming == null)
		{
			return null;
		}
		Map<String, Object> timingJson = new LinkedHashMap<>();
		timingJson.put("wallMs", genTiming[0]);
		timingJson.put("clientMs", genTiming[1]);
		timingJson.put("rebuildMs", genTiming[2]);
		timingJson.put("searchCpuMs", genTiming[3]);
		timingJson.put("searches", genTiming[4]);
		if (genTiming.length > 5)
		{
			timingJson.put("fieldMs", genTiming[5]);
		}
		// Which searches the time went to and how much each explored (a flat A* heuristic shows
		// up as a huge node count).
		List<Object> searchDetails = new ArrayList<>();
		for (AlternativeRoutesService.SearchRecord r : service.getLastSearchRecords())
		{
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("label", r.label);
			detail.put("cpuMs", r.cpuMs);
			detail.put("cost", r.resultCost);
			detail.put("reached", r.reached);
			detail.put("termination", r.termination);
			detail.put("nodes", r.nodesChecked);
			detail.put("transports", r.transportsChecked);
			detail.put("capped", r.capped);
			detail.put("astar", r.astar);
			searchDetails.add(detail);
		}
		timingJson.put("searchDetails", searchDetails);
		return timingJson;
	}

	private File write(Map<String, Object> snapshot) throws java.io.IOException
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "gps-debug");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		File out = new File(dir, "gps-capture-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json");
		Gson gson = plugin.gson().newBuilder().setPrettyPrinting().create();
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))
		{
			gson.toJson(snapshot, writer);
		}
		return out;
	}

	/** A method's identity as the dashboard reads it: type, display info, destination. */
	private static String methodKey(TeleportMethod method)
	{
		return method.getType() + "|" + method.getDisplayInfo() + "|" + method.getDestination();
	}

	/** The directions as JSON rows: text, indexes, ticks and the step kind flags. */
	static List<Object> stepsJson(List<RouteDirections.Step> steps)
	{
		List<Object> stepsJson = new ArrayList<>();
		for (RouteDirections.Step step : steps)
		{
			Map<String, Object> stepJson = new LinkedHashMap<>();
			stepJson.put("text", step.getText());
			stepJson.put("startIndex", step.getStartIndex());
			stepJson.put("endIndex", step.getEndIndex());
			stepJson.put("ticks", step.getTicks());
			stepJson.put("transport", step.isTransport());
			stepJson.put("door", step.isDoor());
			stepJson.put("obstacle", step.isObstacle());
			stepsJson.add(stepJson);
		}
		return stepsJson;
	}

	/** A packed point as {packed, x, y, plane}, or null when undefined. */
	static Map<String, Object> packedPointJson(int packed)
	{
		if (packed == WorldPointUtil.UNDEFINED)
		{
			return null;
		}
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("packed", packed);
		point.put("x", WorldPointUtil.unpackWorldX(packed));
		point.put("y", WorldPointUtil.unpackWorldY(packed));
		point.put("plane", WorldPointUtil.unpackWorldPlane(packed));
		return point;
	}
}
