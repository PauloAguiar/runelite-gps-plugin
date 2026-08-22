package gps.transport.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every requirement the field parsers could not read, kept so a test can FAIL on them. A
 * parse failure drops the gate entirely — the transport becomes usable without its item,
 * level or var — and for weeks that only showed as log noise (an Underground Pass dig was
 * free because its row spelled "Spade"; the Motherlode ladders lost their durations to
 * the varbit column). Recorded alongside the log line; drained by the data lint test.
 */
public final class ParseErrors
{
	private static final List<String> RECORDED = Collections.synchronizedList(new ArrayList<>());

	private ParseErrors()
	{
	}

	static void record(String what, String value)
	{
		RECORDED.add(what + ": " + value);
	}

	/** Returns everything recorded since the last drain, and clears. */
	public static List<String> drain()
	{
		synchronized (RECORDED)
		{
			List<String> out = new ArrayList<>(RECORDED);
			RECORDED.clear();
			return out;
		}
	}
}
