package gps.transport;

import gps.WorldPointUtil;
import gps.transport.parser.ParseErrors;
import gps.transport.parser.QuestParser;
import gps.transport.parser.WorldPointParser;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Review 2026-09-06: three transport rows shipped UNGATED because their quest name matched no
 * Quest enum entry and the parser dropped the gate silently; one row lost its destination to a
 * leading space and fell out of the graph. Both parsers now record parse errors, which the data
 * lint drains, so the next typo is a red build instead of a silent behaviour change.
 */
public class QuestAndCoordinateParserTest
{
	@Before
	public void drain()
	{
		ParseErrors.drain();
	}

	@Test
	public void knownQuestsParseCleanly()
	{
		Set<Quest> quests = new QuestParser().parse("Shadow of the Storm;Recipe for Disaster - King Awowogei");
		assertTrue(quests.contains(Quest.SHADOW_OF_THE_STORM));
		assertTrue(quests.contains(Quest.RECIPE_FOR_DISASTER__KING_AWOWOGEI));
		assertTrue("no errors for valid names", ParseErrors.drain().isEmpty());
	}

	@Test
	public void unknownQuestNameIsRecordedNotDropped()
	{
		Set<Quest> quests = new QuestParser().parse("Shadows of the Storm");
		assertTrue(quests.isEmpty());
		List<String> errors = ParseErrors.drain();
		assertEquals("exactly one recorded error", 1, errors.size());
		assertTrue(errors.get(0), errors.get(0).contains("Shadows of the Storm"));
	}

	@Test
	public void paddedCoordinateStillParses()
	{
		WorldPointParser parser = new WorldPointParser();
		assertEquals((Integer) WorldPointUtil.packWorldPoint(3035, 4852, 0), parser.parse(" 3035 4852 0"));
		assertEquals((Integer) WorldPointUtil.packWorldPoint(3019, 9741, 0), parser.parse("3019 9741 0 "));
		assertTrue("padding is tolerated silently", ParseErrors.drain().isEmpty());
	}

	@Test
	public void malformedCoordinateIsRecorded()
	{
		WorldPointParser parser = new WorldPointParser();
		assertEquals((Integer) Transport.LOCATION_PERMUTATION, parser.parse("3035 4852"));
		assertEquals("a two-part cell is a malformed coordinate", 1, ParseErrors.drain().size());
		assertEquals("empty stays a permutation without an error", (Integer) Transport.LOCATION_PERMUTATION, parser.parse(""));
		assertTrue(ParseErrors.drain().isEmpty());
	}
}
