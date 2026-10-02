package gps;

import java.util.Set;

/**
 * The destination a nearest-bank trip replaced, resumed once the trip completes (a player
 * suggestion: bank mid-route, then carry on). Only the nearest-bank action starts a trip and only
 * an active destination is remembered; any other change of destination (a new pin, a clear,
 * another plugin's target) forgets it, so a resume never surprises. A second bank click during a
 * trip keeps the original destination: what that click replaces is the bank trip itself.
 */
final class BankDetour {
    /** A replaced destination: its targets, label, round-trip flag and world-map pin. */
    static final class Route {
        final Set<Integer> targets;
        final String source;
        final boolean roundTrip;
        final int marker;

        private Route(Set<Integer> targets, String source, boolean roundTrip, int marker) {
            this.targets = targets;
            this.source = source;
            this.roundTrip = roundTrip;
            this.marker = marker;
        }

        /** The route for a destination, or null when none is set. */
        static Route of(Set<Integer> targets, String source, boolean roundTrip, int marker) {
            return targets == null || targets.isEmpty()
                ? null : new Route(Set.copyOf(targets), source, roundTrip, marker);
        }
    }

    private Route saved;
    private boolean active;

    /** Whether a bank trip is under way. */
    synchronized boolean isActive() {
        return active;
    }

    /**
     * The destination this trip will resume, or null: no trip, or a trip that replaced nothing
     * (which is an ordinary destination, labelled and drawn as one).
     */
    synchronized Route pending() {
        return active ? saved : null;
    }

    /**
     * Whether a bank click now would add a stop, that is resume a route after the bank: during a
     * trip, when it saved one; otherwise, when a destination is set (the one it would replace).
     */
    synchronized boolean wouldResume(boolean destinationSet) {
        return active ? saved != null : destinationSet;
    }

    /**
     * The Bank quick button's tooltip: "Nearest bank", or while a click would add a stop, what that
     * does. Bank (and back) keeps its plain tooltip: it returns to where you are anyway.
     */
    static String bankButtonTooltip(boolean addsStop) {
        return addsStop
            ? "<html><b>Add a stop</b> at the nearest bank<br>Your current route resumes after the bank</html>"
            : "Nearest bank";
    }

    /**
     * The directions header's destination line: "Destination set by <source>", or while a bank
     * trip will resume a replaced destination, what happens after the bank ("Bank stop, then the
     * route set by map pin"). Null with nothing to say.
     */
    static String headerLine(String source, Route pending, boolean roundTrip) {
        if (pending != null) {
            String then = pending.source != null ? "the route set by " + pending.source : "your route";
            return (roundTrip ? "Bank and back, then " : "Bank stop, then ") + then;
        }
        return source == null ? null : "Destination set by " + source;
    }

    /**
     * What a new bank trip replaces: the original destination while a trip is already under way,
     * otherwise {@code current}. Read before the destination changes, since the change forgets it.
     */
    synchronized Route replacing(Route current) {
        return active ? saved : current;
    }

    /** A bank trip started; {@code replaced} is resumed when it completes (null: nothing). */
    synchronized void begin(Route replaced) {
        saved = replaced;
        active = true;
    }

    /** Another destination replaced or cleared the trip: nothing will be resumed. */
    synchronized void cancel() {
        saved = null;
        active = false;
    }

    /** The trip completed: the destination to resume (null if none), forgotten either way. */
    synchronized Route complete() {
        Route route = active ? saved : null;
        cancel();
        return route;
    }
}
