package gps.dev;

import java.util.List;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The varbit heatmap model: ordering, the mark bracket, noise, names, the dump. */
public class VarbitWatchTest
{
	private static VarbitWatch watchWith(int... ticksFor42)
	{
		VarbitWatch watch = new VarbitWatch();
		int value = 0;
		for (int tick : ticksFor42)
		{
			watch.record(true, 42, value, value + 1, tick, 0L, 3200, 3200, 0);
			value++;
		}
		return watch;
	}

	@Test
	public void hottestFirstThenMostChanged()
	{
		VarbitWatch watch = new VarbitWatch();
		watch.record(true, 1, 0, 1, 10, 0L, 0, 0, 0);
		watch.record(true, 2, 0, 1, 12, 0L, 0, 0, 0);
		watch.record(true, 3, 0, 1, 12, 0L, 0, 0, 0);
		watch.record(true, 3, 1, 2, 12, 0L, 0, 0, 0);
		List<VarbitWatch.Entry> rows = watch.rows("", false, false, 12);
		assertEquals(3, rows.get(0).id); // same tick as 2 but more changes
		assertEquals(2, rows.get(1).id);
		assertEquals(1, rows.get(2).id);
		assertEquals(2, rows.get(0).count);
		assertEquals(1, rows.get(0).lastFrom);
		assertEquals(2, rows.get(0).lastTo);
	}

	@Test
	public void markBracketsAnAction()
	{
		VarbitWatch watch = new VarbitWatch();
		watch.record(true, 1, 0, 1, 5, 0L, 0, 0, 0);
		watch.mark(10);
		watch.record(true, 2, 0, 1, 11, 0L, 0, 0, 0);
		List<VarbitWatch.Entry> since = watch.rows("", true, false, 11);
		assertEquals(1, since.size());
		assertEquals(2, since.get(0).id);
		assertEquals(2, watch.rows("", false, false, 11).size());
	}

	@Test
	public void tickTimersAreNoisyAndCanBeHidden()
	{
		VarbitWatch noisy = watchWith(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		assertTrue(noisy.isNoisy(noisy.entry(true, 42), 10));
		assertTrue(noisy.rows("", false, true, 10).isEmpty());
		assertEquals(1, noisy.rows("", false, false, 10).size());
		VarbitWatch calm = watchWith(1, 5, 10);
		assertFalse(calm.isNoisy(calm.entry(true, 42), 10));
	}

	@Test
	public void heatCoolsOverTicks()
	{
		VarbitWatch watch = watchWith(100);
		VarbitWatch.Entry entry = watch.entry(true, 42);
		assertEquals(1f, VarbitWatch.heat(entry, 100), 0.001f);
		assertEquals(0.5f, VarbitWatch.heat(entry, 100 + VarbitWatch.COOL_TICKS / 2), 0.001f);
		assertEquals(0f, VarbitWatch.heat(entry, 100 + VarbitWatch.COOL_TICKS * 2), 0.001f);
	}

	@Test
	public void namesComeFromGameval()
	{
		assertEquals("SAILING_BOARDED_BOAT", VarbitWatch.name(true, VarbitID.SAILING_BOARDED_BOAT));
		assertNull(VarbitWatch.name(true, -12345));
		VarbitWatch watch = new VarbitWatch();
		watch.record(true, VarbitID.SAILING_BOARDED_BOAT, 0, 1, 1, 0L, 0, 0, 0);
		assertEquals(1, watch.rows("boarded", false, false, 1).size());
		assertEquals(1, watch.rows(Integer.toString(VarbitID.SAILING_BOARDED_BOAT), false, false, 1).size());
		assertTrue(watch.rows("zzz-no-such-name", false, false, 1).isEmpty());
	}

	@Test
	public void pauseStopsRecordingAndClearForgetsTheMark()
	{
		VarbitWatch watch = new VarbitWatch();
		watch.setPaused(true);
		watch.record(true, 1, 0, 1, 1, 0L, 0, 0, 0);
		assertEquals(0, watch.size());
		watch.setPaused(false);
		watch.record(true, 1, 0, 1, 1, 0L, 0, 0, 0);
		watch.mark(1);
		watch.clear();
		assertEquals(0, watch.size());
		assertEquals(-1, watch.markTick());
	}

	@Test
	public void dumpCarriesEntriesAndHistories()
	{
		VarbitWatch watch = new VarbitWatch();
		watch.record(false, 7, VarbitWatch.UNKNOWN, 3, 4, 0L, 3222, 3218, 0);
		watch.record(false, 7, 3, 4, 9, 0L, 3223, 3218, 0);
		String dump = watch.dump(9);
		assertTrue(dump.contains("varp\t7\t"));
		assertTrue(dump.contains("\t2\t4\t9\t3\t4"));   // count, first, last, lastFrom, lastTo
		assertTrue(dump.contains("varp\t7\t4\t?\t3\t3222\t3218\t0"));
		assertTrue(dump.contains("varp\t7\t9\t3\t4\t3223\t3218\t0"));
	}
}
