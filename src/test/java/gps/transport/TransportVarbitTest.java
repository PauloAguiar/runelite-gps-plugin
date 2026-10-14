package gps.transport;

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import gps.transport.parser.VarCheckType;
import gps.transport.parser.VarRequirement;

public class TransportVarbitTest
{

	@Test
	public void testEqual()
	{
		Map<Integer, Integer> values = new HashMap<>();
		values.put(1, 5);
		VarRequirement v = VarRequirement.varbit(1, 5, VarCheckType.EQUAL);
		assertTrue(v.check(values));
		values.put(1, 4);
		assertFalse(v.check(values));
	}

	@Test
	public void testGreater()
	{
		Map<Integer, Integer> values = new HashMap<>();
		values.put(2, 10);
		VarRequirement v = VarRequirement.varbit(2, 5, VarCheckType.GREATER);
		assertTrue(v.check(values));
		values.put(2, 5);
		assertFalse(v.check(values));
	}

	@Test
	public void testSmaller()
	{
		Map<Integer, Integer> values = new HashMap<>();
		values.put(3, 3);
		VarRequirement v = VarRequirement.varbit(3, 5, VarCheckType.SMALLER);
		assertTrue(v.check(values));
		values.put(3, 5);
		assertFalse(v.check(values));
	}

	@Test
	public void testBitSet()
	{
		Map<Integer, Integer> values = new HashMap<>();
		values.put(4, 0b1010);
		VarRequirement v = VarRequirement.varbit(4, 0b0010, VarCheckType.BIT_SET);
		assertTrue(v.check(values));
		v = VarRequirement.varbit(4, 0b0100, VarCheckType.BIT_SET);
		assertFalse(v.check(values));
	}

	@Test
	public void testCooldownMinutes()
	{
		Map<Integer, Integer> values = new HashMap<>();
		long nowMinutes = System.currentTimeMillis() / 60000;
		values.put(5, (int) (nowMinutes - 10)); // stored timestamp 10 minutes ago

		VarRequirement v = VarRequirement.varbit(5, 5, VarCheckType.COOLDOWN_MINUTES);
		assertTrue(v.check(values)); // 10 > 5

		values.put(5, (int) (nowMinutes - 3)); // 3 minutes ago
		assertFalse(v.check(values)); // 3 > 5 is false
	}
	/**
	 * A clause may list alternatives with a double bar: any one holding satisfies it (the portal
	 * nexus keeps a teleport in any of 45 slot varbits). Semicolons still separate clauses.
	 */
	@Test
	public void alternativesPassWhenAnyHolds()
	{
		java.util.Set<VarRequirement> parsed = gps.transport.parser.VarRequirementParser.forVarbits().parse("3=1;1=5||2=5");
		assertEquals(2, parsed.size());
		VarRequirement either = null;
		for (VarRequirement requirement : parsed)
		{
			if (requirement.getId() != 3)
			{
				either = requirement;
			}
		}
		assertEquals(java.util.List.of(1, 2), either.ids());
		Map<Integer, Integer> values = new HashMap<>();
		values.put(1, 0);
		values.put(2, 5);
		assertTrue(either.check(values));
		assertTrue(either.checkWith(id -> id == 2 ? 5 : 0));
		values.put(2, 0);
		assertFalse(either.check(values));
		assertFalse(either.checkWith(id -> 0));
		assertTrue(gps.transport.parser.VarRequirementParser.forVarbits().parse("1=5||2=5").iterator().next()
			.equals(gps.transport.parser.VarRequirementParser.forVarbits().parse("1=5||2=5").iterator().next()));
	}
}
