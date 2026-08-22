package gps.dev;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The varbit heatmap's model: every varbit/varp change seen since tracking started, per-id
 * counts and histories, a "mark" to bracket an in-game action ("Mark, change respawn point,
 * read what flipped"), and a noise classifier so tick timers don't drown the list. Pure
 * Java — the panel renders it, the plugin feeds it, the unit test drives it.
 */
final class VarbitWatch
{
	/** Unknown previous value: the first time we see an id we only know its new value. */
	static final int UNKNOWN = Integer.MIN_VALUE;
	/** History kept per id (newest first). */
	static final int HISTORY_CAP = 200;
	/** Ticks until a change has cooled fully in the heat scale. */
	static final int COOL_TICKS = 60;
	/** Noisy: changed in at least this many of the last {@link #NOISE_WINDOW} ticks. */
	static final int NOISE_WINDOW = 10;
	static final int NOISE_HITS = 6;

	static final class Change
	{
		final int tick;
		final long atMillis;
		final int from;
		final int to;
		final int x;
		final int y;
		final int plane;

		Change(int tick, long atMillis, int from, int to, int x, int y, int plane)
		{
			this.tick = tick;
			this.atMillis = atMillis;
			this.from = from;
			this.to = to;
			this.x = x;
			this.y = y;
			this.plane = plane;
		}
	}

	static final class Entry
	{
		final boolean varbit;
		final int id;
		int count;
		int firstTick;
		int lastTick;
		int lastFrom = UNKNOWN;
		int lastTo;
		final Deque<Change> history = new ArrayDeque<>();

		Entry(boolean varbit, int id)
		{
			this.varbit = varbit;
			this.id = id;
		}

		String kind()
		{
			return varbit ? "varbit" : "varp";
		}

		String name()
		{
			return VarbitWatch.name(varbit, id);
		}

		/** Changes within the last {@code window} ticks before {@code nowTick}. */
		int changesWithin(int nowTick, int window)
		{
			int hits = 0;
			int lastCounted = Integer.MIN_VALUE;
			for (Change change : history)
			{
				if (change.tick < nowTick - window)
				{
					break;
				}
				if (change.tick != lastCounted)
				{
					hits++;
					lastCounted = change.tick;
				}
			}
			return hits;
		}
	}

	private final Map<Long, Entry> entries = new LinkedHashMap<>();
	private int markTick = -1;
	private boolean paused;

	private static long key(boolean varbit, int id)
	{
		return (varbit ? 1L << 40 : 0L) | (id & 0xFFFFFFFFL);
	}

	synchronized void record(boolean varbit, int id, int from, int to, int tick, long atMillis,
		int x, int y, int plane)
	{
		if (paused)
		{
			return;
		}
		Entry entry = entries.computeIfAbsent(key(varbit, id), k ->
		{
			Entry e = new Entry(varbit, id);
			e.firstTick = tick;
			return e;
		});
		entry.count++;
		entry.lastTick = tick;
		entry.lastFrom = from;
		entry.lastTo = to;
		entry.history.addFirst(new Change(tick, atMillis, from, to, x, y, plane));
		while (entry.history.size() > HISTORY_CAP)
		{
			entry.history.removeLast();
		}
	}

	synchronized void mark(int tick)
	{
		markTick = tick;
	}

	synchronized int markTick()
	{
		return markTick;
	}

	synchronized void clear()
	{
		entries.clear();
		markTick = -1;
	}

	synchronized void setPaused(boolean paused)
	{
		this.paused = paused;
	}

	synchronized boolean isPaused()
	{
		return paused;
	}

	synchronized int size()
	{
		return entries.size();
	}

	synchronized Entry entry(boolean varbit, int id)
	{
		return entries.get(key(varbit, id));
	}

	boolean isNoisy(Entry entry, int nowTick)
	{
		return entry.changesWithin(nowTick, NOISE_WINDOW) >= NOISE_HITS;
	}

	/** 1.0 = changed this tick, fading linearly to 0 at {@link #COOL_TICKS} ticks ago. */
	static float heat(Entry entry, int nowTick)
	{
		int age = Math.max(0, nowTick - entry.lastTick);
		return Math.max(0f, 1f - (float) age / COOL_TICKS);
	}

	/**
	 * The rows to show, hottest first (most recent change, then most changes). {@code filter}
	 * matches id or name (case-insensitive); {@code sinceMark} keeps only ids that changed at or
	 * after the mark; {@code hideNoisy} drops tick timers and the like.
	 */
	synchronized List<Entry> rows(String filter, boolean sinceMark, boolean hideNoisy, int nowTick)
	{
		String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
		List<Entry> out = new ArrayList<>();
		for (Entry entry : entries.values())
		{
			if (sinceMark && (markTick < 0 || entry.lastTick < markTick))
			{
				continue;
			}
			if (hideNoisy && isNoisy(entry, nowTick))
			{
				continue;
			}
			if (!needle.isEmpty())
			{
				String name = entry.name();
				if (!Integer.toString(entry.id).contains(needle)
					&& (name == null || !name.toLowerCase(Locale.ROOT).contains(needle)))
				{
					continue;
				}
			}
			out.add(entry);
		}
		out.sort(Comparator.comparingInt((Entry e) -> -e.lastTick).thenComparingInt(e -> -e.count));
		return out;
	}

	/** TSV of every entry plus each one's history — the attachment for an issue or a field note. */
	synchronized String dump(int nowTick)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("# Varbit watch dump at tick ").append(nowTick)
			.append(markTick >= 0 ? ", mark at tick " + markTick : ", no mark").append('\n');
		sb.append("kind\tid\tname\tcount\tfirstTick\tlastTick\tlastFrom\tlastTo\n");
		List<Entry> sorted = new ArrayList<>(entries.values());
		sorted.sort(Comparator.comparingInt((Entry e) -> -e.lastTick));
		for (Entry entry : sorted)
		{
			sb.append(entry.kind()).append('\t').append(entry.id).append('\t')
				.append(entry.name() == null ? "" : entry.name()).append('\t')
				.append(entry.count).append('\t').append(entry.firstTick).append('\t')
				.append(entry.lastTick).append('\t').append(value(entry.lastFrom)).append('\t')
				.append(entry.lastTo).append('\n');
		}
		sb.append("\n# history: kind\tid\ttick\tfrom\tto\tx\ty\tplane\n");
		for (Entry entry : sorted)
		{
			List<Change> oldestFirst = new ArrayList<>(entry.history);
			Collections.reverse(oldestFirst);
			for (Change change : oldestFirst)
			{
				sb.append(entry.kind()).append('\t').append(entry.id).append('\t').append(change.tick)
					.append('\t').append(value(change.from)).append('\t').append(change.to)
					.append('\t').append(change.x).append('\t').append(change.y).append('\t')
					.append(change.plane).append('\n');
			}
		}
		return sb.toString();
	}

	static String value(int value)
	{
		return value == UNKNOWN ? "?" : Integer.toString(value);
	}

	// ---- names: RuneLite's gameval constants, reflected once ----

	private static final class Names
	{
		static final Map<Integer, String> VARBITS = reflect("net.runelite.api.gameval.VarbitID");
		static final Map<Integer, String> VARPS = reflect("net.runelite.api.gameval.VarPlayerID");

		private static Map<Integer, String> reflect(String className)
		{
			Map<Integer, String> names = new HashMap<>();
			try
			{
				for (Field field : Class.forName(className).getFields())
				{
					if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
					{
						names.putIfAbsent(field.getInt(null), field.getName());
					}
				}
			}
			catch (ReflectiveOperationException | RuntimeException e)
			{
				// No names, ids only — the heatmap still works.
			}
			return names;
		}
	}

	/** The gameval constant name for the id, or null when RuneLite has none. */
	static String name(boolean varbit, int id)
	{
		return (varbit ? Names.VARBITS : Names.VARPS).get(id);
	}
}
