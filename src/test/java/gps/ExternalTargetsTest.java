package gps;

import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Plan step L21: a destination another plugin sends (Quest Helper's NPC or object tile), as a
 * target set. Each tile expands like a map pin; when any expanded tile is a mapped transport
 * ORIGIN, the origins alone remain (the interactable side of a cave or stairs), otherwise the
 * expansion stands.
 */
public class ExternalTargetsTest
{
	private static final int A = WorldPointUtil.packWorldPoint(3200, 3200, 0);
	private static final int B = WorldPointUtil.packWorldPoint(3300, 3300, 0);

	@Test
	public void aTransportOriginAmongTheTargetsWinsAlone()
	{
		assertEquals(Set.of(A), Destinations.externalTargets(Set.of(A, B), null, tile -> tile == A));
	}

	@Test
	public void withoutAnOriginEveryTargetStands()
	{
		assertEquals(Set.of(A, B), Destinations.externalTargets(Set.of(A, B), null, tile -> false));
		assertEquals(Set.of(A, B), Destinations.externalTargets(Set.of(A, B), null, null));
	}
}
