package gps;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The running build's identity: the release version from the bundled plugin manifest and the git
 * commit stamped at build time, so reports always name exactly what ran (no constant to keep in
 * sync). "unknown" in a dev build where a file is not on the classpath.
 */
public final class BuildInfo
{
	// A BARE constant, browsed as-is: the hub review reads any dynamic URL construction (the old
	// pre-filled ?title=&body=) as network I/O of player data. Context travels via the clipboard.
	static final String GITHUB_NEW_ISSUE = "https://github.com/PauloAguiar/runelite-gps-plugin/issues/new";

	private BuildInfo()
	{
	}

	/** The release version from {@code runelite-plugin.properties}. */
	public static String pluginVersion()
	{
		return property("/runelite-plugin.properties", "version");
	}

	/** The git commit from {@code gps-build.properties} (stamped by processResources). */
	public static String buildCommit()
	{
		return property("/gps-build.properties", "commit");
	}

	private static String property(String resource, String key)
	{
		try (InputStream in = BuildInfo.class.getResourceAsStream(resource))
		{
			if (in != null)
			{
				Properties props = new Properties();
				props.load(in);
				String value = props.getProperty(key);
				if (value != null && !value.isEmpty())
				{
					return value;
				}
			}
		}
		catch (IOException ignored)
		{
			// Fall through to "unknown".
		}
		return "unknown";
	}
}
