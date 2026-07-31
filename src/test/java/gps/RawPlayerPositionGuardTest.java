package gps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * ARCHITECTURAL GUARD: live player position must flow through the boat-aware
 * {@code WorldPointUtil.fromLocalInstance(Client, Player)} overload. Aboard a boat the raw
 * player position lives in the boat's sub-WorldView (template-band coordinates), and the raw
 * idioms below regressed independently THREE times — the plugin's recalc start, the debug
 * snapshot, and the progress tracker — each one silently breaking the moment a player
 * boarded. A new use of these idioms in main sources is a test failure, not a field capture.
 */
public class RawPlayerPositionGuardTest
{
	private static final String[] FORBIDDEN = {
		".getLocalPlayer().getWorldLocation()",
		"fromLocalInstance(client, player.getLocalLocation())",
		"fromLocalInstance(client, local.getLocalLocation())",
	};

	@Test
	public void mainSourcesNeverReadRawPlayerPosition() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java/gps")))
		{
			for (Path file : (Iterable<Path>) files.filter(
				f -> f.toString().endsWith(".java"))::iterator)
			{
				// The overload itself legitimately reads the raw local as its ashore fallback.
				if (file.getFileName().toString().equals("WorldPointUtil.java"))
				{
					continue;
				}
				String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				for (String idiom : FORBIDDEN)
				{
					if (source.contains(idiom))
					{
						offenders.add(file.getFileName() + ": " + idiom);
					}
				}
			}
		}
		assertTrue("Raw player-position idioms break aboard a boat; route through"
			+ " WorldPointUtil.fromLocalInstance(client, player):\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}
}
