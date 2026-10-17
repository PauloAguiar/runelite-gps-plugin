package gps;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import net.runelite.client.externalplugins.PluginHubManifest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A player's debug snapshot from the hub build read "pluginVersion: unknown" (2026-10-16). The
 * plugin manifest is "standard" build type, so the hub replaces our build script: the step that
 * bundles runelite-plugin.properties and stamps the git commit never runs there, and neither
 * file is on the classpath. The hub's own manifest for the installed plugin carries the version
 * instead; the stamped files remain the dev build's source.
 */
public class BuildInfoTest
{
	@Test
	public void aHubBuildReportsTheHubManifestsVersion()
	{
		PluginHubManifest.DisplayData hub = new PluginHubManifest.DisplayData();
		hub.setVersion("9.9.9");
		assertEquals("9.9.9", BuildInfo.pluginVersion(hub));
		assertEquals("hub", BuildInfo.buildCommit("unknown", true));
	}

	@Test
	public void aDevBuildReadsTheBundledManifestAndTheStamp() throws IOException
	{
		Properties manifest = new Properties();
		try (InputStream in = new FileInputStream("runelite-plugin.properties"))
		{
			manifest.load(in);
		}
		assertEquals(manifest.getProperty("version"), BuildInfo.pluginVersion(null));
		assertEquals("abc1234", BuildInfo.buildCommit("abc1234", true));
		assertEquals("unknown", BuildInfo.buildCommit("unknown", false));
	}
}
