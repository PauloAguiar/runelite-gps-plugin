package gps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Review 2026-09-06: the pohMount*, sailingAssumeSummon and sailingTeleportAbandon toggles did
 * nothing when flipped because the plugin's "route-affecting keys" list was a hand-maintained
 * regex that never learned them. This test derives the set the routing engine actually reads
 * from its sources and fails the moment a key is missing from the list.
 */
public class RouteAffectingKeysTest
{
	private static final Pattern OVERRIDE_READ = Pattern.compile("override\\(\"(\\w+)\"");
	private static final Pattern DIRECT_READ = Pattern.compile("\\bconfig\\.(\\w+)\\(\\)");

	/** Config reads inside the engine that do NOT change what routing computes. */
	private static final Set<String> NOT_ROUTING = Set.of(
		"rememberBank" // persistence only: whether the bank snapshot is stored across sessions
	);

	@Test
	public void everyKeyTheEngineReadsRegeneratesRoutes() throws IOException
	{
		Set<String> keys = new TreeSet<>();
		for (String source : List.of(
			"src/main/java/gps/pathfinder/PathfinderConfig.java",
			"src/main/java/gps/transport/TransportTypeConfig.java"))
		{
			String text = Files.readString(Paths.get(source), StandardCharsets.UTF_8);
			for (Pattern pattern : List.of(OVERRIDE_READ, DIRECT_READ))
			{
				Matcher m = pattern.matcher(text);
				while (m.find())
				{
					keys.add(m.group(1));
				}
			}
		}
		assertTrue("the scan must find the engine's config reads", keys.size() > 20);

		Set<String> missing = new TreeSet<>();
		for (String key : keys)
		{
			if (!NOT_ROUTING.contains(key) && !ShortestPathPlugin.affectsRouting(key))
			{
				missing.add(key);
			}
		}
		assertTrue("routing reads these keys but a change to them never recomputes: " + missing, missing.isEmpty());
	}

	@Test
	public void theFieldToggles()
	{
		for (String key : List.of("pohMountGlory", "pohMountXerics", "pohMountDigsite", "pohMountMythical",
			"sailingAssumeSummon", "sailingTeleportAbandon", "useCharterShips", "costSailing", "avoidWilderness"))
		{
			assertTrue(key + " must regenerate routes", ShortestPathPlugin.affectsRouting(key));
		}
		// Display order only: handled by a re-sort, not a regeneration.
		assertFalse(ShortestPathPlugin.affectsRouting("sailingKeepSailing"));
		assertFalse(ShortestPathPlugin.affectsRouting("colourPath"));
		assertFalse(ShortestPathPlugin.affectsRouting(null));
	}

	@Test
	public void pluginMessageOverridesOnlyAcceptDeclaredKeys()
	{
		Set<String> known = ShortestPathPlugin.knownConfigKeys();
		assertTrue(known.contains("avoidWilderness"));
		assertTrue(known.contains("useTeleportationItems"));
		assertFalse("a typo must be rejected, not stored forever", known.contains("avoidWildernes"));
		assertTrue("the declared surface is large", known.size() > 100);
	}

	/** Guard for the source scan: the files exist where the test expects them. */
	@Test
	public void engineSourcesExist()
	{
		Path p = Paths.get("src/main/java/gps/pathfinder/PathfinderConfig.java");
		assertTrue(Files.exists(p));
	}
}
