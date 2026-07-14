package gps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.SplitFlagMap;

/**
 * Dev tool, not a test: builds destinations-wiki.tsv for the GPS search from the OSRS wiki —
 * every article using {@code {{Infobox Location}}} (places, landmarks, dungeons) or
 * {@code {{Infobox Activity}}} (minigames) whose infobox carries a {@code {{Map|x=..|y=..}}} pin.
 * This fills the gap the cache dump leaves: locations without an in-game world-map label
 * (e.g. Giants' Foundry) become searchable without hand-curating each one.
 * <p>
 * Names already present in destinations.tsv / destinations-curated.tsv are skipped (the cache dump
 * and hand curation stay authoritative), and pins with no walkable tile nearby (instanced/unmapped
 * zones) are dropped against the plugin's own collision map.
 * <p>
 * Run with: {@code ./gradlew test --tests gps.WikiLocationDumperTest -DwikiLocations.dump=true}
 * (the plugin build forwards {@code wikiLocations.*} system properties to the test JVM); the
 * output default is src/main/resources/destinations-wiki.tsv — review the diff and commit.
 */
public class WikiLocationDumperTest
{
	private static final String API = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT =
		"RuneLite GPS plugin location dump (github.com/PauloAguiar/runelite-gps-plugin)";
	private static final int BATCH = 50;
	private static final long THROTTLE_MILLIS = 300;

	// The infobox map pin: {{Map|x=3360|y=3151|plane=0|...}} (named params, any order).
	private static final Pattern MAP_TEMPLATE = Pattern.compile("\\{\\{Map\\|([^{}]*)}}", Pattern.CASE_INSENSITIVE);
	private static final Pattern INFOBOX_TYPE = Pattern.compile("\\|\\s*type\\s*=\\s*([^|\\n]*)", Pattern.CASE_INSENSITIVE);

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	public void dump() throws Exception
	{
		Assume.assumeTrue("Enable with -DwikiLocations.dump=true", Boolean.getBoolean("wikiLocations.dump"));
		Path resources = Paths.get(System.getProperty("wikiLocations.resources", "src/main/resources"));
		Path output = Paths.get(System.getProperty("wikiLocations.output",
			resources.resolve("destinations-wiki.tsv").toString()));

		Set<String> existingNames = new HashSet<>();
		for (String file : new String[]{"destinations.tsv", "destinations-curated.tsv"})
		{
			Path path = resources.resolve(file);
			if (Files.exists(path))
			{
				for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
				{
					String[] cols = line.split("\t");
					if (cols.length >= 2 && !"category".equals(cols[0]))
					{
						existingNames.add(normalize(cols[1]));
					}
				}
			}
		}
		System.out.println("existing names to dedupe against: " + existingNames.size());

		// category|name -> row; TreeMap for a stable, reviewable file order.
		Map<String, String> rows = new TreeMap<>();
		Set<String> emitted = new HashSet<>(existingNames);
		int[] scanned = new int[1];
		String today = LocalDate.now().toString();
		// Wiki pins in instanced/unmapped zones (cutscene areas, Memory instances) have no walkable
		// tile nearby in the plugin's collision map and would produce nonsense routes.
		CollisionMap collisionMap = new CollisionMap(SplitFlagMap.fromResources());

		Map<String, List<int[]>> candidates = new TreeMap<>();
		harvest("Template:Infobox Location", false, emitted, candidates, scanned);
		harvest("Template:Infobox Activity", true, emitted, candidates, scanned);
		resolvePins(rows, candidates, collisionMap, today);

		Files.createDirectories(output.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			writer.write("category\tname\tx\ty\tplane\tverified_source\tverified_date\n");
			for (String row : rows.values())
			{
				writer.write(row);
				writer.write('\n');
			}
		}
		System.out.println("pages scanned: " + scanned[0] + ", entries written: " + rows.size());
		System.out.println("output: " + output.toAbsolutePath());
	}

	private void harvest(String template, boolean activity, Set<String> emitted, Map<String, List<int[]>> candidates,
		int[] scanned) throws Exception
	{
		List<String> titles = transcludingTitles(template);
		System.out.println(template + ": " + titles.size() + " pages");
		for (int i = 0; i < titles.size(); i += BATCH)
		{
			for (Map.Entry<String, String> page : wikitexts(titles.subList(i, Math.min(i + BATCH, titles.size()))).entrySet())
			{
				scanned[0]++;
				String title = page.getKey();
				if (title.contains("/") || title.contains("(historical)") || title.contains("Leagues"))
				{
					continue;
				}
				String category = categorize(page.getValue(), activity);
				if (category == null)
				{
					continue;
				}
				List<int[]> pins = mapPins(page.getValue());
				if (pins.isEmpty() || !emitted.add(normalize(title)))
				{
					continue;
				}
				candidates.put(category + "|" + title, pins);
			}
		}
	}

	/**
	 * Resolves each candidate to its first REACHABLE pin, or drops it: instanced and cutscene zones
	 * are walkable in the collision map but disconnected from the world graph, so a search from
	 * Lumbridge with every transport enabled (everything-mode planning config) is the oracle. Pages
	 * often pin an interior first and the entrance later (dungeons, altars), so every pin gets a
	 * chance. Slow (minutes — unreachable pins each flood the map) but this only runs when
	 * regenerating the data.
	 */
	private static void resolvePins(Map<String, String> rows, Map<String, List<int[]>> candidates,
		CollisionMap collisionMap, String today)
	{
		final Thread clientThread = Thread.currentThread();
		net.runelite.api.Client client = (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[]{net.runelite.api.Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return net.runelite.api.GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		// Everything on: booleans true (minus the restricting toggles), items ALL, best jewellery box.
		ShortestPathConfig config = org.mockito.Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name) && !"enableSeasonalTransports".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.ALL;
			}
			if (type == JewelleryBoxTier.class)
			{
				return JewelleryBoxTier.ORNATE;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		gps.pathfinder.PathfinderConfig planning =
			new gps.pathfinder.TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();

		final int lumbridge = WorldPointUtil.packWorldPoint(3221, 3218, 0);
		List<String> dropped = new ArrayList<>();
		int salvagedByLaterPin = 0;
		for (Map.Entry<String, List<int[]>> candidate : candidates.entrySet())
		{
			int[] chosen = null;
			List<int[]> pins = candidate.getValue();
			for (int i = 0; i < pins.size() && chosen == null; i++)
			{
				int[] pin = pins.get(i);
				Set<Integer> ring = Destinations.walkableTargets(collisionMap,
					WorldPointUtil.packWorldPoint(pin[0], pin[1], pin[2]));
				if (ring.isEmpty())
				{
					continue;
				}
				gps.pathfinder.Pathfinder pathfinder = new gps.pathfinder.Pathfinder(planning, lumbridge, ring);
				pathfinder.run();
				if (pathfinder.getResult().isReached())
				{
					chosen = pin;
					if (i > 0)
					{
						salvagedByLaterPin++;
					}
				}
			}
			if (chosen == null)
			{
				dropped.add(candidate.getKey());
				continue;
			}
			String key = candidate.getKey();
			String category = key.substring(0, key.indexOf('|'));
			String title = key.substring(key.indexOf('|') + 1);
			rows.put(key, category + "\t" + title + "\t" + chosen[0] + "\t" + chosen[1] + "\t" + chosen[2]
				+ "\tosrs-wiki-infobox-map\t" + today);
		}
		System.out.println("salvaged by a later pin: " + salvagedByLaterPin);
		System.out.println("unreachable entries dropped: " + dropped.size() + " " + dropped);
	}

	/**
	 * The search category for a page, from its infobox type: activities count when they are
	 * minigames or raids ("minigame") or fixed-location bosses/activities like Barrows and the
	 * Blast mine ("landmark") — but not random events, forestry events, or roaming distractions.
	 * Locations map city/town/village/island to "place", dungeons and caves to "dungeon",
	 * everything else (mountains, ruins, guilds, buildings, ...) to "landmark". Null skips the page.
	 */
	private static String categorize(String wikitext, boolean activity)
	{
		Matcher typeMatcher = INFOBOX_TYPE.matcher(wikitext);
		String type = typeMatcher.find()
			? typeMatcher.group(1).replaceAll("\\[\\[|]]", "").trim().toLowerCase(Locale.ROOT) : "";
		if (activity)
		{
			if (type.contains("minigame") || type.contains("raid"))
			{
				return "minigame";
			}
			return type.equals("boss") || type.equals("activity") ? "landmark" : null;
		}
		if (type.contains("city") || type.contains("town") || type.contains("village") || type.contains("island"))
		{
			return "place";
		}
		if (type.contains("dungeon") || type.contains("cave"))
		{
			return "dungeon";
		}
		return "landmark";
	}

	/**
	 * Every plausible in-world {@code {{Map}}} pin on the page, in order: [x, y, plane] each. Many
	 * pages pin an interior first and the entrance later, so the reachability pass tries them all.
	 * Handles both named ({@code x=..|y=..}) and positional ({@code {{Map|2984,3291|...}}}) pins.
	 */
	private static List<int[]> mapPins(String wikitext)
	{
		List<int[]> pins = new ArrayList<>();
		Matcher matcher = MAP_TEMPLATE.matcher(wikitext);
		while (matcher.find())
		{
			Integer x = null;
			Integer y = null;
			int plane = 0;
			for (String param : matcher.group(1).split("\\|"))
			{
				String[] pair = param.split("=", 2);
				if (pair.length != 2)
				{
					// Positional coordinate pair: {{Map|2984,3291|mtype=pin}}.
					Matcher pos = Pattern.compile("^\\s*(\\d{4}),\\s*(\\d{4,5})\\s*$").matcher(param);
					if (pos.matches())
					{
						x = Integer.parseInt(pos.group(1));
						y = Integer.parseInt(pos.group(2));
					}
					continue;
				}
				String key = pair[0].trim().toLowerCase(Locale.ROOT);
				try
				{
					if ("x".equals(key))
					{
						x = Integer.parseInt(pair[1].trim());
					}
					else if ("y".equals(key))
					{
						y = Integer.parseInt(pair[1].trim());
					}
					else if ("plane".equals(key))
					{
						plane = Integer.parseInt(pair[1].trim());
					}
				}
				catch (NumberFormatException e)
				{
					// A templated/expression param — not a plain pin; keep scanning.
				}
			}
			if (x != null && y != null && x >= 1000 && x <= 4600 && y >= 2000 && y <= 13000 && plane >= 0 && plane <= 3)
			{
				pins.add(new int[]{x, y, plane});
			}
		}
		return pins;
	}

	/** Every mainspace title transcluding the template, via the paginated embeddedin API. */
	private List<String> transcludingTitles(String template) throws IOException, InterruptedException
	{
		List<String> titles = new ArrayList<>();
		String cont = null;
		do
		{
			String url = API + "?action=query&list=embeddedin&eititle=" + encode(template)
				+ "&einamespace=0&eilimit=500&format=json"
				+ (cont != null ? "&eicontinue=" + encode(cont) : "");
			JsonObject json = get(url);
			for (JsonElement e : json.getAsJsonObject("query").getAsJsonArray("embeddedin"))
			{
				titles.add(e.getAsJsonObject().get("title").getAsString());
			}
			cont = json.has("continue") ? json.getAsJsonObject("continue").get("eicontinue").getAsString() : null;
		}
		while (cont != null);
		return titles;
	}

	/** Raw wikitext per title for one batch (the API caps titles at 50 per request). */
	private Map<String, String> wikitexts(List<String> titles) throws IOException, InterruptedException
	{
		String url = API + "?action=query&prop=revisions&rvprop=content&rvslots=main&format=json"
			+ "&titles=" + encode(String.join("|", titles));
		JsonObject json = get(url);
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("query").getAsJsonObject("pages").entrySet())
		{
			JsonObject page = entry.getValue().getAsJsonObject();
			if (!page.has("revisions"))
			{
				continue;
			}
			JsonObject slot = page.getAsJsonArray("revisions").get(0).getAsJsonObject()
				.getAsJsonObject("slots").getAsJsonObject("main");
			out.put(page.get("title").getAsString(), slot.get("*").getAsString());
		}
		return out;
	}

	private JsonObject get(String url) throws IOException, InterruptedException
	{
		Thread.sleep(THROTTLE_MILLIS);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200)
		{
			throw new IOException("wiki API " + response.statusCode() + " for " + url);
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String normalize(String name)
	{
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
