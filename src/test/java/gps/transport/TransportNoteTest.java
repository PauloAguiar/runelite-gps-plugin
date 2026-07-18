package gps.transport;

import gps.transport.parser.TransportRecord;
import gps.transport.parser.TsvParser;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The optional Note column: a free-text advisory shown on the route step ("fire arrow needed",
 * "can fail") that must never gate the transport — it carries no requirement semantics, so a
 * noted transport stays exactly as routable as an un-noted one.
 */
public class TransportNoteTest
{
	private static final String HEADER =
		"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info\tNote\n";

	@Test
	public void noteColumnIsParsedAndExposed()
	{
		List<TransportRecord> records = new TsvParser().parse(HEADER
			+ "2446 3316 0\t2495 9716 0\tEnter Cave entrance 3213\t\t\t\t\t\t3\t\tfire arrow needed for the bridge ahead\n");
		Transport transport = new Transport(records.get(0), TransportType.TRANSPORT);
		assertEquals("fire arrow needed for the bridge ahead", transport.getNote());
	}

	@Test
	public void missingOrEmptyNoteIsNull()
	{
		// Short row (file without the column) and empty trailing column both mean "no note".
		List<TransportRecord> records = new TsvParser().parse(HEADER
			+ "2446 3316 0\t2495 9716 0\tEnter Cave entrance 3213\t\t\t\t\t\t3\t\n"
			+ "2446 3317 0\t2495 9717 0\tEnter Cave entrance 3213\t\t\t\t\t\t3\t\t\n");
		assertNull(new Transport(records.get(0), TransportType.TRANSPORT).getNote());
		assertNull(new Transport(records.get(1), TransportType.TRANSPORT).getNote());
	}

	@Test
	public void notedTransportCarriesNoRequirements()
	{
		List<TransportRecord> records = new TsvParser().parse(HEADER
			+ "2446 3316 0\t2495 9716 0\tCross Bridge 3254\t\t\t\t\t\t3\t\tbring a fire arrow\n");
		Transport transport = new Transport(records.get(0), TransportType.TRANSPORT);
		// Advisory only: no quests, no items, no skill levels appear because of a note.
		assertTrue(transport.getQuests().isEmpty());
		assertNull(transport.getItemRequirements());
		for (int level : transport.getSkillLevels())
		{
			assertEquals(0, level);
		}
	}
}
