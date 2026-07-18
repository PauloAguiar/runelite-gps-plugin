package gps.dev;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The refusal-message parsers behind the audit's automatic requirement harvesting. */
public class TransportAuditParseTest
{
	@Test
	public void skillRequirementsParseToTheTsvFormat()
	{
		assertEquals("72 Agility", TransportAuditPlugin.parseSkillRequirement(
			"You need an Agility level of 72 to use this shortcut."));
		assertEquals("56 Agility", TransportAuditPlugin.parseSkillRequirement(
			"you need an agility level of 56 to enter this."));
		assertEquals("70 Strength", TransportAuditPlugin.parseSkillRequirement(
			"You need a Strength level of 70 to bend these bars."));
		assertNull(TransportAuditPlugin.parseSkillRequirement(
			"Your Agility level is not high enough."));
	}

	@Test
	public void questRequirementsParseToTheQuestName()
	{
		assertEquals("Underground Pass", TransportAuditPlugin.parseQuestRequirement(
			"You must have completed the Underground Pass quest to enter."));
		assertEquals("Song of the Elves", TransportAuditPlugin.parseQuestRequirement(
			"You need to have completed Song of the Elves to pass."));
		assertNull(TransportAuditPlugin.parseQuestRequirement(
			"You need a rope to climb down."));
	}
}
