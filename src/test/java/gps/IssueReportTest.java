package gps;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The "report an issue" button must stay hub-review-clean: the routing context travels via the
 * CLIPBOARD, and the GitHub link is a bare constant — the old pre-filled {@code ?title=&body=}
 * URL read as network I/O of player data and flagged the 0.12.0 release for human review.
 */
public class IssueReportTest
{
	@Test
	public void newIssueLinkCarriesNoData()
	{
		assertTrue(ShortestPathPlugin.GITHUB_NEW_ISSUE.startsWith(
			"https://github.com/PauloAguiar/runelite-gps-plugin/issues/new"));
		assertFalse("the new-issue link must be bare — context goes via the clipboard, never the URL",
			ShortestPathPlugin.GITHUB_NEW_ISSUE.contains("?"));
	}

	@Test
	public void versionIsReadFromTheBundledManifest()
	{
		// build.gradle bundles runelite-plugin.properties onto the classpath, so the plugin reports
		// its real release version (not a hard-coded constant that can drift).
		String version = ShortestPathPlugin.pluginVersion();
		assertTrue("version must be read from the manifest, not the 'unknown' fallback: " + version,
			version.matches("\\d+\\.\\d+\\.\\d+"));
	}
}
