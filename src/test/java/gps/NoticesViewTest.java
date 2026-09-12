package gps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Panel series P5: the status banner's decision table, out of the panel class. Nothing while
 * calculating or while a route reaches; every route stopping short says why (unlocks you lack,
 * or no known route); nothing found for a target hints at a broader mode unless already in All;
 * an empty list after arrival says arrived (or already there); otherwise no destination is set.
 */
public class NoticesViewTest
{
	@Test
	public void nothingWhileCalculatingOrWhenARouteReaches()
	{
		assertNull(NoticesView.status(true, false, false, false, true, false, false, false));
		assertNull(NoticesView.status(false, true, false, false, true, false, false, false));
	}

	@Test
	public void everyRouteStoppingShortSaysWhy()
	{
		NoticesView.Status unlocks = NoticesView.status(false, true, true, true, true, false, false, false);
		assertEquals(NoticesView.Kind.WARNING, unlocks.kind);
		assertTrue(unlocks.text.startsWith("<b>Not reachable with what you have.</b>"));
		NoticesView.Status unknown = NoticesView.status(false, true, true, false, true, false, false, false);
		assertTrue(unknown.text.startsWith("<b>No known route to this destination.</b>"));
	}

	@Test
	public void nothingFoundHintsAtABroaderModeUnlessAlreadyInAll()
	{
		NoticesView.Status owned = NoticesView.status(false, false, false, false, true, false, false, false);
		assertEquals(NoticesView.Kind.WARNING, owned.kind);
		assertTrue(owned.text.contains("Try a broader mode"));
		NoticesView.Status all = NoticesView.status(false, false, false, false, true, false, false, true);
		assertFalse(all.text.contains("Try a broader mode"));
	}

	@Test
	public void arrivalAndNoDestination()
	{
		NoticesView.Status arrived = NoticesView.status(false, false, false, false, false, true, false, false);
		assertEquals(NoticesView.Kind.OK, arrived.kind);
		assertEquals("Arrived at your destination.", arrived.text);
		assertEquals("You're already at your destination.",
			NoticesView.status(false, false, false, false, false, true, true, false).text);
		NoticesView.Status none = NoticesView.status(false, false, false, false, false, false, false, false);
		assertEquals(NoticesView.Kind.INFO, none.kind);
		assertTrue(none.text.startsWith("<b>No destination set.</b>"));
	}
}
