package gps;

import java.util.ArrayList;
import java.util.List;

/**
 * The destination search's recent-selections list: the last {@link #LIMIT} picks, most recent
 * first, persisted across sessions as one config string (rows of {@code category\tname\tpacked}).
 * Pure functions — the plugin owns the stored copy and the config write.
 */
final class SearchHistory {
    static final int LIMIT = 10;

    private SearchHistory() {
    }

    /** A new list with {@code entry} at the front, duplicates removed, capped at {@link #LIMIT}. */
    static List<Destinations.Entry> push(List<Destinations.Entry> history, Destinations.Entry entry) {
        List<Destinations.Entry> out = new ArrayList<>();
        out.add(entry);
        for (Destinations.Entry old : history) {
            if (out.size() >= LIMIT)
                break;
            if (old.packedPosition != entry.packedPosition || !old.name.equals(entry.name))
                out.add(old);
        }
        return out;
    }

    static String serialize(List<Destinations.Entry> history) {
        StringBuilder sb = new StringBuilder();
        for (Destinations.Entry entry : history) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(entry.category).append('\t').append(entry.name).append('\t').append(entry.packedPosition);
        }
        return sb.toString();
    }

    static List<Destinations.Entry> deserialize(String raw) {
        return deserialize(raw, LIMIT);
    }

    /** Deserializes an entry list with a caller-chosen cap (the favourites list allows more rows). */
    static List<Destinations.Entry> deserialize(String raw, int limit) {
        List<Destinations.Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty())
            return out;
        for (String line : raw.split("\\R")) {
            String[] fields = line.split("\t");
            if (fields.length < 3)
                continue;
            try {
                out.add(new Destinations.Entry(fields[0], fields[1], Integer.parseInt(fields[2])));
                if (out.size() >= limit)
                    break;
            }
            catch (NumberFormatException e) {
                // A malformed row (hand-edited config) is dropped rather than poisoning the list.
            }
        }
        return out;
    }
}
