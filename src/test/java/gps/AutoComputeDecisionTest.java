package gps;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Covers the auto-compute decision ({@link RouteSession#shouldAutoCompute}): alternatives are
 * (re)generated when the target changes or when the last generation was allowed fewer routes than
 * wanted now (its budget grew meanwhile). The route budget itself never depends on whether the
 * side panel is shown ({@link RouteController#routeLimitFor}).
 */
public class AutoComputeDecisionTest
{
	private static final Set<Integer> TARGETS = Set.of(WorldPointUtil.packWorldPoint(3200, 3200, 0));
	private static final Set<Integer> OTHER_TARGETS = Set.of(WorldPointUtil.packWorldPoint(3300, 3300, 0));
	private static final int FULL = 5;
	private static final int PRIMARY_ONLY = 1;

	@Test
	public void routeBudgetDoesNotDependOnThePanel()
	{
		// The overlay's route must be the same with the panel open or hidden: both states run the
		// configured budget, never a primary-only search (the "different route once the panel
		// opens" experience).
		assertEquals(RouteController.routeLimitFor(true, 10), RouteController.routeLimitFor(false, 10));
		assertEquals(10, RouteController.routeLimitFor(false, 10));
		assertEquals(1, RouteController.routeLimitFor(false, 0));
		assertEquals(25, RouteController.routeLimitFor(true, 99));
	}

	@Test
	public void noTargetsNeverComputes()
	{
		assertFalse("No destination -> nothing to compute",
			RouteSession.shouldAutoCompute(Set.of(), Set.of(), 0, FULL));
	}

	@Test
	public void newTargetComputes()
	{
		assertTrue("A destination the last generation didn't cover must compute",
			RouteSession.shouldAutoCompute(TARGETS, OTHER_TARGETS, FULL, FULL));
		assertTrue("The first destination of the session must compute",
			RouteSession.shouldAutoCompute(TARGETS, Set.of(), 0, PRIMARY_ONLY));
	}

	@Test
	public void sameTargetAlreadyGeneratedSkips()
	{
		assertFalse("Same destination, already generated at the wanted limit -> skip",
			RouteSession.shouldAutoCompute(TARGETS, TARGETS, FULL, FULL));
		assertFalse("Panel hidden after a primary-only run for the same destination -> skip",
			RouteSession.shouldAutoCompute(TARGETS, TARGETS, PRIMARY_ONLY, PRIMARY_ONLY));
	}

	@Test
	public void openingThePanelCatchesUpAPrimaryOnlyGeneration()
	{
		assertTrue("Panel opened after a hidden primary-only run -> full generation",
			RouteSession.shouldAutoCompute(TARGETS, TARGETS, PRIMARY_ONLY, FULL));
	}

	@Test
	public void closingThePanelDoesNotDiscardAFullGeneration()
	{
		assertFalse("Panel hidden after a full generation -> keep it, no primary-only redo",
			RouteSession.shouldAutoCompute(TARGETS, TARGETS, FULL, PRIMARY_ONLY));
	}

	@Test
	public void showMoreRoutesIsNotUndoneByTheAutoCompute()
	{
		assertFalse("A generation grown past the default (Show more) must not be regenerated smaller",
			RouteSession.shouldAutoCompute(TARGETS, TARGETS, FULL + 10, FULL));
	}
}
