package gps;

import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Panel series P4: the route list's text rules, out of the panel class. A duration reads as
 * seconds under a minute and minutes plus seconds above; the busy note switches to "longer
 * routes" once the found routes span more than three times the cheapest (so the good ones are
 * in); an empty list is never in that state.
 */
public class RouteListViewTest
{
	private static RouteOption costing(int cost)
	{
		return new RouteOption(List.of(), List.of(), List.of(), List.of(), cost, cost, true, Set.of(), List.of(), 0);
	}

	@Test
	public void durationsReadAsSecondsThenMinutes()
	{
		assertEquals("0s", RouteListView.formatDuration(0));
		assertEquals("59s", RouteListView.formatDuration(59));
		assertEquals("1m 0s", RouteListView.formatDuration(60));
		assertEquals("2m 5s", RouteListView.formatDuration(125));
	}

	@Test
	public void theBusyNoteSaysLongerRoutesOnceTheSpreadPassesTheMultiple()
	{
		assertFalse(RouteListView.searchingLongerRoutes(List.of()));
		assertFalse(RouteListView.searchingLongerRoutes(List.of(costing(100))));
		assertFalse("three times exactly is not past the multiple",
			RouteListView.searchingLongerRoutes(List.of(costing(100), costing(300))));
		assertTrue(RouteListView.searchingLongerRoutes(List.of(costing(100), costing(200), costing(301))));
	}
}
