package app.umbra.ui.model;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Back stack of the single-Activity UI. Pure Java so navigation rules are unit tested.
 *
 * <p>Rules: locking clears every sensitive destination; routes whose backend is pending cannot be
 * entered; the Calls tab does not exist in the offline build.
 */
public final class Navigator {
    private final Deque<Route> stack = new ArrayDeque<>();
    private final FeatureAvailability features;
    private HomeTab tab = HomeTab.CHATS;

    public Navigator(FeatureAvailability features) { this.features = features; stack.push(Route.of(Route.Kind.LOCKED)); }

    public Route current() { return stack.peek(); }
    public HomeTab tab() { return tab; }
    /** Destination under the current one, or null at a root. */
    public Route previous() {
        if (stack.size() < 2) return null;
        java.util.Iterator<Route> it = stack.iterator(); it.next(); return it.next();
    }
    public int depth() { return stack.size(); }

    /** Drops every destination (including open chats) and shows the lock screen. */
    public void lock() { stack.clear(); stack.push(Route.of(Route.Kind.LOCKED)); tab = HomeTab.CHATS; }
    public void onboarding() { stack.clear(); stack.push(Route.of(Route.Kind.ONBOARDING)); }
    public void home() { stack.clear(); stack.push(Route.of(Route.Kind.HOME)); }

    public boolean selectTab(HomeTab next) {
        if (!HomeTab.visible(features).contains(next)) return false;
        tab = next; home(); return true;
    }

    /** @return false when the destination is not allowed (locked, pending backend or not in flavor). */
    public boolean push(Route route) {
        Route top = current();
        if (top.kind() == Route.Kind.LOCKED || top.kind() == Route.Kind.ONBOARDING) return false;
        if (!allowed(route)) return false;
        if (route.equals(top)) return true;
        stack.push(route); return true;
    }

    /** Replaces the top destination (e.g. chat -> verify result -> chat) without growing the stack. */
    public boolean replace(Route route) {
        if (stack.size() <= 1 || !allowed(route)) return false;
        stack.pop(); stack.push(route); return true;
    }

    /** @return false when already at a root; the caller then locks and moves the task to back. */
    public boolean back() {
        if (stack.size() <= 1) return false;
        stack.pop(); return true;
    }

    public boolean allowed(Route route) {
        return switch (route.kind()) {
            case LOCKED, ONBOARDING, HOME -> false; // Only via lock()/onboarding()/home().
            case GROUP_CHAT -> features.available(Feature.GROUP_CHAT);
            case CALL, INCOMING_CALL -> features.available(Feature.VOICE_CALLS);
            default -> true;
        };
    }
}
