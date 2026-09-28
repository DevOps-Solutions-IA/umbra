package app.umbra;

import app.umbra.ui.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Navigation rules: lock clears sensitive routes, pending features are unreachable, offline has no calls. */
public class UiNavigationTest {
    private static FeatureAvailability connected() { return FeatureAvailability.forBuild(true, true); }
    private static FeatureAvailability offline() { return FeatureAvailability.forBuild(false, false); }

    @Test public void startsLockedAndRefusesContentWhileLocked() {
        Navigator nav = new Navigator(connected());
        assertEquals(Route.Kind.LOCKED, nav.current().kind());
        assertFalse(nav.push(Route.of(Route.Kind.CHAT, "peer")));
        assertFalse(nav.push(Route.of(Route.Kind.DEVICES)));
        assertEquals(1, nav.depth());
    }
    @Test public void lockDropsEveryOpenDestination() {
        Navigator nav = new Navigator(connected());
        nav.home();
        assertTrue(nav.push(Route.of(Route.Kind.CHAT, "peer")));
        assertTrue(nav.push(Route.of(Route.Kind.CONTACT, "peer")));
        assertTrue(nav.push(Route.of(Route.Kind.VERIFY, "peer")));
        nav.lock();
        assertEquals(Route.Kind.LOCKED, nav.current().kind());
        assertEquals(1, nav.depth());
        assertEquals(HomeTab.CHATS, nav.tab());
    }
    @Test public void backWalksTheStackAndReportsRoot() {
        Navigator nav = new Navigator(connected());
        nav.home();
        nav.push(Route.of(Route.Kind.CHAT, "peer"));
        nav.push(Route.of(Route.Kind.CONTACT, "peer"));
        assertEquals(Route.of(Route.Kind.CHAT, "peer"), nav.previous());
        assertTrue(nav.back());
        assertEquals(Route.Kind.CHAT, nav.current().kind());
        assertTrue(nav.back());
        assertEquals(Route.Kind.HOME, nav.current().kind());
        assertFalse(nav.back());
        assertNull(nav.previous());
    }
    @Test public void duplicatePushDoesNotGrowStack() {
        Navigator nav = new Navigator(connected()); nav.home();
        nav.push(Route.of(Route.Kind.CHAT, "a")); nav.push(Route.of(Route.Kind.CHAT, "a"));
        assertEquals(2, nav.depth());
        assertTrue(nav.replace(Route.of(Route.Kind.CHAT, "b")));
        assertEquals("b", nav.current().arg());
        assertEquals(2, nav.depth());
    }
    @Test public void groupConversationIsUnreachableWhileBackendIsPending() {
        Navigator nav = new Navigator(connected()); nav.home();
        assertFalse(nav.push(Route.of(Route.Kind.GROUP_CHAT, "g")));
        assertTrue("group creation UI is a prepared flow", nav.push(Route.of(Route.Kind.NEW_GROUP)));
    }
    @Test public void offlineHasNoCallsTabOrCallRoutes() {
        Navigator nav = new Navigator(offline()); nav.home();
        assertFalse(HomeTab.visible(offline()).contains(HomeTab.CALLS));
        assertFalse(nav.selectTab(HomeTab.CALLS));
        assertFalse(nav.push(Route.of(Route.Kind.CALL, "c")));
        assertFalse(nav.push(Route.of(Route.Kind.INCOMING_CALL, "c")));
        assertTrue(nav.selectTab(HomeTab.NEARBY));
        assertEquals(HomeTab.NEARBY, nav.tab());
    }
    @Test public void connectedShowsCallsTab() {
        assertEquals(java.util.List.of(HomeTab.CHATS, HomeTab.CALLS, HomeTab.NEARBY, HomeTab.SETTINGS), HomeTab.visible(connected()));
        Navigator nav = new Navigator(connected()); nav.home();
        assertTrue(nav.push(Route.of(Route.Kind.CALL, "c")));
    }
    @Test public void onlyLockAndOnboardingRoutesAreNotSensitive() {
        for (Route.Kind kind : Route.Kind.values())
            assertEquals(kind.name(), kind != Route.Kind.LOCKED && kind != Route.Kind.ONBOARDING, new Route(kind, null).sensitive());
    }
}
