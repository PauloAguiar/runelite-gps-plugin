package gps;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;

/**
 * The running build's identity, so reports always name exactly what ran (no constant to keep in
 * sync). A dev build reads the plugin manifest bundled by processResources and the git commit it
 * stamps. A hub build has neither: the manifest is "standard" build type, so the hub replaces the
 * build script and those files never reach the classpath (a player's snapshot read "unknown",
 * 2026-10-16); the hub's own manifest for the installed plugin supplies the version there, and the
 * commit reads "hub" (the hub pin is the release commit).
 */
public final class BuildInfo {
    // A BARE constant, browsed as-is: the hub review reads any dynamic URL construction (the old
    // pre-filled ?title=&body=) as network I/O of player data. Context travels via the clipboard.
    static final String GITHUB_NEW_ISSUE = "https://github.com/PauloAguiar/runelite-gps-plugin/issues/new";

    private BuildInfo() {
    }

    /** The release version: the hub's manifest on a hub build, else the bundled plugin manifest. */
    public static String pluginVersion() {
        return pluginVersion(ExternalPluginManager.getDisplayData(ShortestPathPlugin.class));
    }

    static String pluginVersion(PluginHubManifest.DisplayData hub) {
        if (hub != null && hub.getVersion() != null && !hub.getVersion().isEmpty())
            return hub.getVersion();
        return property("/runelite-plugin.properties", "version");
    }

    /** The git commit stamped by a dev build, "hub" on a hub build, else "unknown". */
    public static String buildCommit() {
        return buildCommit(property("/gps-build.properties", "commit"),
            ExternalPluginManager.getDisplayData(ShortestPathPlugin.class) != null);
    }

    static String buildCommit(String stamped, boolean hub) {
        return "unknown".equals(stamped) && hub ? "hub" : stamped;
    }

    private static String property(String resource, String key) {
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String value = props.getProperty(key);
                if (value != null && !value.isEmpty())
                    return value;
            }
        }
        catch (IOException ignored) {
            // Fall through to "unknown".
        }
        return "unknown";
    }
}
