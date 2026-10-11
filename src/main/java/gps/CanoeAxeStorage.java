package gps;

import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.client.config.ConfigManager;

/**
 * Any canoe station stores one axe for the player, shared by every station, and a stored axe
 * works without being withdrawn (wiki, Canoe Station). The game shows the storage only in chat,
 * like the balloon log crates (see BalloonLogStorage), so the store and withdraw lines flip the
 * "Axe stored at a canoe station" config flag the canoe rows read; the player can also set it by
 * hand. The lines are provisional until confirmed from the field: an unmatched line changes nothing.
 */
final class CanoeAxeStorage {
    static final String CONFIG_KEY = "canoeAxeStored";
    private static final Pattern STORED = Pattern.compile("^You (store|leave) your .*axe (at|with) the canoe station");
    private static final Pattern WITHDRAWN = Pattern.compile("^You (withdraw|take|retrieve) your .*axe .*(from|back from) the canoe station");

    private CanoeAxeStorage() {
    }

    /** TRUE when the line says an axe was stored, FALSE when withdrawn, null for any other line. */
    static Boolean parse(String message) {
        if (STORED.matcher(message).find())
            return Boolean.TRUE;
        if (WITHDRAWN.matcher(message).find())
            return Boolean.FALSE;
        return null;
    }

    /** Flips the flag from a game line (the game and dialog channels carry these). */
    static void track(ChatMessageType type, String message, ConfigManager configManager, String configGroup) {
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX)
            return;
        Boolean stored = parse(message);
        if (stored != null)
            configManager.setConfiguration(configGroup, CONFIG_KEY, stored);
    }
}
