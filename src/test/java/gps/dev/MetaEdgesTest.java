package gps.dev;

import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportLoader;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The single-file metadata model: Meta-tagged rows are loadable for the dev audit's
 * needs-confirmation view, while the runtime loader keeps parsing tagged rows exactly like
 * untagged ones (the Meta column is invisible to routing).
 */
public class MetaEdgesTest
{
	@Test
	public void tagsAreLoadableAndPlentiful()
	{
		List<MetaEdges.Entry> entries = MetaEdges.load();
		assertTrue("expected hundreds of tagged rows, got " + entries.size(), entries.size() > 500);
		assertTrue("the Honour varbit gate must be tagged ?varbits",
			entries.stream().anyMatch(e -> e.tags.contains("?varbits")));
		assertTrue("derived rows must be tagged ~geometry",
			entries.stream().filter(e -> e.tags.contains("~geometry")).count() > 500);
	}

	@Test
	public void runtimeLoaderIsUnaffectedByTags()
	{
		// A calibrated derived row (ladder-up, family mode 4 ticks) parses with its real values.
		int origin = WorldPointUtil.packWorldPoint(1204, 3119, 0);
		int dest = WorldPointUtil.packWorldPoint(1203, 3118, 1);
		Set<Transport> transports = TransportLoader.loadAllFromResources()
			.getOrDefault(origin, Set.of());
		Transport ladder = transports.stream()
			.filter(t -> t.getDestination() == dest)
			.findFirst().orElseThrow(() -> new AssertionError("tagged ladder row not loaded"));
		assertEquals("calibrated duration must survive the Meta column", 4, ladder.getDuration());
	}
}
