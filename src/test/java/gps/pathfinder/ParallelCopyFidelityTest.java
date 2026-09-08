package gps.pathfinder;

import gps.ShortestPathConfig;
import gps.TeleportationItem;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan step N8: a parallel-search sibling (seed workers, the walk search) must see exactly the
 * refresh-derived state the chain's config sees, or the same query returns different routes
 * depending on which worker ran it ("consistent over fast"). The review found the hand-written
 * copy method missed fields as they were added. This audit compares every declared field of the
 * planning config against its sibling by content, and every field that is legitimately per-copy
 * must be listed here with its reason, so a new field fails the build until someone decides.
 */
@RunWith(MockitoJUnitRunner.class)
public class ParallelCopyFidelityTest
{
	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	/** Fields that are per-copy by design, each with the reason it need not match the source. */
	private static final Set<String> PER_COPY = Set.of(
		// Identity and wiring.
		"client", "config", "planningSource",
		// Filled lazily by the copy's own refresh pass; the copy never refreshes.
		"questStates", "varbitValues", "varPlayerValues", "itemSnapshots", "refreshPassActive", "itemNames",
		// Per-thread collision map (the paged graph mutates it during a search).
		"map",
		// Copied by value in the copy constructor; its content is compared below.
		"transportTypeConfig"
	);

	@Test
	public void everyFieldIsCopiedOrDeclaredPerCopy() throws Exception
	{
		when(config.calculationCutoff()).thenReturn(120);
		lenient().when(config.currencyThreshold()).thenReturn(10000000);
		lenient().when(config.useTeleportationItems()).thenReturn(TeleportationItem.ALL);
		lenient().when(config.useFairyRings()).thenReturn(true);
		lenient().when(config.useTeleportationSpells()).thenReturn(true);
		lenient().when(config.useSailing()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(i -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.setPlanningMode(true);
		planning.setBypassItemPossession(true);
		planning.refresh();
		PathfinderConfig sibling = planning.copyForParallelSearch();

		List<String> mismatches = new ArrayList<>();
		for (Field field : PathfinderConfig.class.getDeclaredFields())
		{
			if (Modifier.isStatic(field.getModifiers()) || PER_COPY.contains(field.getName()))
			{
				continue;
			}
			field.setAccessible(true);
			Object source = field.get(planning);
			Object copy = field.get(sibling);
			if (source != copy && !Objects.deepEquals(source, copy))
			{
				mismatches.add(field.getName() + " (source=" + brief(source) + ", copy=" + brief(copy) + ")");
			}
		}
		Field typeConfigField = PathfinderConfig.class.getDeclaredField("transportTypeConfig");
		typeConfigField.setAccessible(true);
		gps.transport.TransportTypeConfig sourceTypes = (gps.transport.TransportTypeConfig) typeConfigField.get(planning);
		gps.transport.TransportTypeConfig copyTypes = (gps.transport.TransportTypeConfig) typeConfigField.get(sibling);
		if (sourceTypes.getTeleportationItemSetting() != copyTypes.getTeleportationItemSetting())
		{
			mismatches.add("transportTypeConfig.teleportationItemSetting");
		}
		for (gps.transport.TransportType type : gps.transport.TransportType.values())
		{
			if (sourceTypes.isEnabled(type) != copyTypes.isEnabled(type)
				|| sourceTypes.isEnabledInConfig(type) != copyTypes.isEnabledInConfig(type)
				|| sourceTypes.getCost(type) != copyTypes.getCost(type))
			{
				mismatches.add("transportTypeConfig state for " + type);
			}
		}
		assertTrue("fields a parallel-search sibling does not share with its source; copy them in"
			+ " copyForParallelSearch or list them as per-copy with a reason:\n  " + String.join("\n  ", mismatches),
			mismatches.isEmpty());
	}

	private static String brief(Object value)
	{
		if (value == null)
		{
			return "null";
		}
		String text = value.toString();
		return text.length() > 60 ? text.substring(0, 57) + "..." : text;
	}
}
