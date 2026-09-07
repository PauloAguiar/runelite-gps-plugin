package gps.transport;

import net.runelite.api.Quest;

import gps.transport.parser.ParseErrors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import gps.WorldPointUtil;

/**
 * Unit tests for validating transport data integrity.
 * These tests validate the consistency and correctness of transport data files.
 */
public class TransportDataLintTest
{

	@Test
	public void testNoDuplicateOriginDestinationPairs()
	{
		// Load all transport data from resources
		HashMap<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();

		// Track all origin-destination-type combinations to check for exact duplicates
		Set<String> transportSignatures = new HashSet<>();
		Map<String, String> duplicateInfo = new HashMap<>();
		List<String> duplicatesFound = new ArrayList<>();

		for (Map.Entry<Integer, Set<Transport>> entry : transports.entrySet())
		{
			int origin = entry.getKey();
			Set<Transport> transportSet = entry.getValue();

			for (Transport transport : transportSet)
			{
				int destination = transport.getDestination();

				// Create a comprehensive signature that includes all transport properties
				// This helps distinguish between legitimate different transport variants vs
				// true duplicates
				// ObjectInfo is critical for distinguishing fairy rings with different item
				// requirements
				// (e.g., dramen staff vs lunar staff are represented by different objectIDs in
				// objectInfo)
				// The display info is there of the one edge case in the fairy ring system.
				// There are two options to go to Zanaris, one is with the code `B K S` and the
				// other is with the `zanaris` option.
				// only the display info is different between those two options so we need to
				// include it in the signature.
				String signature = String.format(
					"%s -> %s [%s] '%s' Consumable:%s Items:%s Quests:%s Varbits:%s VarPlayers:%s ObjectInfo:%s",
					WorldPointUtil.unpackWorldX(origin) + " " + WorldPointUtil.unpackWorldY(origin) + " "
						+ WorldPointUtil.unpackWorldPlane(origin),
					WorldPointUtil.unpackWorldX(destination) + " " + WorldPointUtil.unpackWorldY(destination) + " "
						+ WorldPointUtil.unpackWorldPlane(destination),
					transport.getType(),
					transport.getDisplayInfo() != null ? transport.getDisplayInfo() : "",
					transport.isConsumable(),
					transport.getItemRequirements() != null ? transport.getItemRequirements().toString() : "none",
					transport.getQuests().toString(),
					transport.getVarbits().toString(),
					transport.getVarPlayers().toString(),
					transport.getObjectInfo() != null ? transport.getObjectInfo() : "none");

				// Check if this exact transport signature already exists
				if (transportSignatures.contains(signature))
				{
					String existingInfo = duplicateInfo.get(signature);
					String newInfo = String.format("MaxWilderness: %d, Skills: %s",
						transport.getMaxWildernessLevel(),
						Arrays.toString(transport.getSkillLevels()));

					duplicatesFound.add(String.format("Exact duplicate transport: %s\n" +
							"First occurrence: %s\n" +
							"Duplicate occurrence: %s\n",
						signature, existingInfo, newInfo));
				}
				else
				{
					// Add this signature to our tracking set
					transportSignatures.add(signature);

					// Store information about this transport for error reporting
					String info = String.format("MaxWilderness: %d, Skills: %s",
						transport.getMaxWildernessLevel(),
						Arrays.toString(transport.getSkillLevels()));
					duplicateInfo.put(signature, info);
				}
			}
		}

		// Report results
		if (!duplicatesFound.isEmpty())
		{
			StringBuilder message = new StringBuilder();
			message.append(String.format("Found %d duplicate transport entries in the data files:\n\n",
				duplicatesFound.size()));
			for (String duplicate : duplicatesFound)
			{
				message.append(duplicate).append("\n");
			}
			Assert.fail(message.toString());
		}

		// If we get here, no exact duplicates were found
		System.out.printf("Successfully validated %d unique transport signatures across all transport data files.\n",
			transportSignatures.size());
	}
	/**
	 * The data must parse CLEANLY: a requirement the parser cannot read is dropped, and the
	 * transport becomes usable without its gate. For weeks that was log noise only — an
	 * Underground Pass dig spelled "Spade" (free without one), the Motherlode ladders put their
	 * duration in the varbit column. Now it fails the build.
	 */
	@Test
	public void everyRequirementParses()
	{
		ParseErrors.drain();
		HashMap<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
		Assert.assertTrue("transports must load", !all.isEmpty());
		List<String> errors = ParseErrors.drain();
		Assert.assertTrue("requirements that failed to parse (the gate is DROPPED for each):\n  "
			+ String.join("\n  ", errors), errors.isEmpty());
	}

	/**
	 * Issue #17: the house Respawn Portal's Prifddinas row had no gate at all — every other
	 * respawn row carries its unlock varbit. Song of the Elves is the Prifddinas unlock.
	 */
	@Test
	public void prifddinasRespawnPortalIsQuestGated()
	{
		boolean found = false;
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : set)
			{
				if ("Respawn Portal (Prifddinas)".equals(transport.getDisplayInfo()))
				{
					found = true;
					Assert.assertTrue("Prifddinas respawn must require Song of the Elves, got "
						+ transport.getQuests(), transport.getQuests().contains(Quest.SONG_OF_THE_ELVES));
				}
				if ("Respawn Portal (Lumbridge)".equals(transport.getDisplayInfo()))
				{
					Assert.assertTrue("the default respawn has no quest gate", transport.getQuests().isEmpty());
					// The *_SPAWN varbits are SELECTION flags (one per respawn point, field-verified
					// 2026-08-16: setting Falador flips FALADOR_SPAWN 0 -> 1); Lumbridge is the
					// default when none is set, so its row must require every known flag to be 0.
					Assert.assertEquals("Lumbridge must be gated on all six selection flags", 6,
						transport.getVarbits().size());
				}
			}
		}
		Assert.assertTrue("the Prifddinas respawn row must exist", found);
	}
	/**
	 * The Monkey Madness I chain to Ape Atoll (Daero -> Waydar's glider -> Lumdo's boat) was
	 * ungated and its two flights were plain rows, so anyone got routed through it and the legs
	 * showed as "Travel Waydar" / "Travel Lumdo" instead of a glider and a boat. Quest Helper's
	 * varbits gate it (MM_DAERO >= 7 after the hangar puzzle, MM_LUMDO >= 3 after Waydar's
	 * Crash Island briefing), and the flights/boat are method-typed so they have catalog entries
	 * and "Glider to Crash Island" / "Boat to Ape Atoll" wording.
	 */
	@Test
	public void monkeyMadnessChainIsGatedAndTyped()
	{
		Map<String, List<Transport>> byObject = new HashMap<>();
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : set)
			{
				String info = transport.getObjectInfo();
				if (info != null && (info.startsWith("Travel Daero") || info.startsWith("Travel Waydar")
					|| info.startsWith("Travel Lumdo")))
				{
					byObject.computeIfAbsent(info.substring(0, info.lastIndexOf(' ')), k -> new ArrayList<>()).add(transport);
				}
			}
		}
		Assert.assertEquals("Daero's ride", 1, byObject.getOrDefault("Travel Daero", List.of()).size());
		Assert.assertEquals("Waydar's two flights", 2, byObject.getOrDefault("Travel Waydar", List.of()).size());
		Assert.assertEquals("Lumdo's boat both ways", 2, byObject.getOrDefault("Travel Lumdo", List.of()).size());
		for (Transport t : byObject.get("Travel Daero"))
		{
			Assert.assertEquals(1, t.getVarbits().size());
		}
		for (Transport t : byObject.get("Travel Waydar"))
		{
			Assert.assertEquals("a glider flight, not a plain row", TransportType.GNOME_GLIDER, t.getType());
			Assert.assertTrue("named destination: " + t.getDisplayInfo(),
				"Crash Island".equals(t.getDisplayInfo()) || "Gnome Stronghold (Waydar)".equals(t.getDisplayInfo()));
			Assert.assertEquals(1, t.getVarbits().size());
		}
		for (Transport t : byObject.get("Travel Lumdo"))
		{
			Assert.assertEquals(TransportType.BOAT, t.getType());
			Assert.assertTrue("named destination: " + t.getDisplayInfo(),
				"Ape Atoll".equals(t.getDisplayInfo()) || "Crash Island".equals(t.getDisplayInfo()));
			Assert.assertEquals(1, t.getVarbits().size());
		}
	}
	/**
	 * Charter arrivals land ON THE DECK (plane 1): the game leaves you on the ship and the
	 * paired Cross Gangplank row is the step down to the dock. With dockside destinations the
	 * path skipped the gangplank entirely and drew nothing while the player stood on plane 1
	 * (field report 2026-08-24, Port Tyras). Land's End is the one port with no gangplank pair
	 * in the data yet, so it still lands dockside.
	 */
	@Test
	public void charterArrivalsLandOnTheDeck()
	{
		int deckArrivals = 0;
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : set)
			{
				if (!TransportType.CHARTER_SHIP.equals(transport.getType()))
				{
					continue;
				}
				int plane = WorldPointUtil.unpackWorldPlane(transport.getDestination());
				boolean landsEnd = WorldPointUtil.unpackWorldX(transport.getDestination()) == 1496
					&& WorldPointUtil.unpackWorldY(transport.getDestination()) == 3403;
				// The Pandemonium also lands dockside until its charter gangplank pair is field-verified
				// (two ships share the east dock; see charter_ships.tsv).
				boolean pandemonium = WorldPointUtil.unpackWorldX(transport.getDestination()) == 3063
					&& WorldPointUtil.unpackWorldY(transport.getDestination()) == 2999;
				Assert.assertTrue("charter to " + transport.getDisplayInfo()
					+ " must arrive on the ship's deck (plane 1)", plane == 1 || landsEnd || pandemonium);
				if (plane == 1)
				{
					deckArrivals++;
				}
			}
		}
		Assert.assertTrue("deck arrivals expected", deckArrivals > 100);
	}
	/**
	 * Sailors' amulet destinations unlock by inspecting their Sailors' Marker (gameval
	 * SAILORS_AMULET_*, field-verified via the varbit watch 2026-08-28): Port Roberts and
	 * Deepfin Point carry their unlock varbit; The Pandemonium is the amulet's home port and
	 * needs none.
	 */
	@Test
	public void sailorsAmuletDestinationsCarryTheirUnlockVarbits()
	{
		int roberts = 0, deepfin = 0, redRock = 0, pandemonium = 0;
		for (Set<Transport> set : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : set)
			{
				String info = transport.getDisplayInfo();
				if (info == null || !info.startsWith("Sailors' amulet:"))
				{
					continue;
				}
				if (info.endsWith("Port Roberts"))
				{
					roberts++;
					Assert.assertEquals("Port Roberts needs its marker varbit", 1, transport.getVarbits().size());
				}
				else if (info.endsWith("Deepfin Point"))
				{
					deepfin++;
					Assert.assertEquals("Deepfin needs its marker varbit", 1, transport.getVarbits().size());
				}
				else if (info.endsWith("Red Rock"))
				{
					redRock++;
					Assert.assertEquals("Red Rock needs its marker varbit", 1, transport.getVarbits().size());
				}
				else if (info.endsWith("The Pandemonium"))
				{
					pandemonium++;
					Assert.assertEquals("the home port has no unlock", 0, transport.getVarbits().size());
				}
			}
		}
		Assert.assertTrue("all four amulet rows must exist",
			roberts == 1 && deepfin == 1 && redRock == 1 && pandemonium == 1);
	}
}



