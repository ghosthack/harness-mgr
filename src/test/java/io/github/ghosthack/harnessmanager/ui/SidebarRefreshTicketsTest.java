package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SidebarRefreshTicketsTest {

    private final SidebarRefreshTickets tickets = new SidebarRefreshTickets();

    @Test
    void aSupersedingNullRefreshStillReselectsThePendingTarget() {
        long created = tickets.issue("hm-new"); // the user's Open… finished
        long external = tickets.issue(null);    // an agent's spawn/kill lands meanwhile
        assertFalse(tickets.isCurrent(created));
        assertTrue(tickets.isCurrent(external));
        assertEquals("hm-new", tickets.takeReselect());
        assertNull(tickets.takeReselect(), "applied once, then the selection is left alone");
    }

    @Test
    void aNewerReselectReplacesAnOlderOne() {
        tickets.issue("hm-a");
        long newest = tickets.issue("hm-b");
        assertTrue(tickets.isCurrent(newest));
        assertEquals("hm-b", tickets.takeReselect());
    }

    @Test
    void aFailedRefreshDropsItsPendingTargetSoALaterOneDoesNotStealTheSelection() {
        long created = tickets.issue("hm-new");
        tickets.failed(created);
        long later = tickets.issue(null); // e.g. an agent's spawn, much later
        assertTrue(tickets.isCurrent(later));
        assertNull(tickets.takeReselect());
    }

    @Test
    void aFailureOlderThanThePendingTargetLeavesItAlone() {
        long older = tickets.issue(null);
        long created = tickets.issue("hm-new");
        tickets.failed(older);
        assertTrue(tickets.isCurrent(created));
        assertEquals("hm-new", tickets.takeReselect());
    }

    @Test
    void aNewerFailureAlsoDropsAnOlderPendingTarget() {
        tickets.issue("hm-new");
        long plain = tickets.issue(null);
        tickets.failed(plain);
        assertNull(tickets.takeReselect());
    }

    @Test
    void plainRefreshesNeverReselect() {
        long only = tickets.issue(null);
        assertTrue(tickets.isCurrent(only));
        assertNull(tickets.takeReselect());
    }
}
