package gps;

import java.nio.file.Files;
import java.nio.file.Paths;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

@SuppressWarnings("unchecked")
public class ShortestPathPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// The deck-packer launcher installs this plugin's jar into ~/.runelite/sideloaded-plugins
		// on every Bolt/Deck launch, and a developer-mode client side-loads that folder too — so
		// this dev client would otherwise load the plugin TWICE. Both copies share the
		// runelite.shortestpathplugin enabled key, and the duplicate's off-state immediately
		// re-disables the copy being toggled on. Remove the managed jar first; the deck launcher
		// reinstalls it on its next run.
		try
		{
			Files.deleteIfExists(Paths.get(System.getProperty("user.home"),
				".runelite", "sideloaded-plugins", "gps.jar"));
		}
		catch (Exception e)
		{
			System.err.println("Could not remove sideloaded gps.jar (another client running?) — "
				+ "the plugin may load twice: " + e);
		}
		// The transport audit is a dev-only companion (test sources, never packaged for the hub):
		// it highlights traversal objects the transport/door data doesn't know about.
		ExternalPluginManager.loadBuiltin(ShortestPathPlugin.class, gps.dev.TransportAuditPlugin.class);
		RuneLite.main(args);
	}
}
