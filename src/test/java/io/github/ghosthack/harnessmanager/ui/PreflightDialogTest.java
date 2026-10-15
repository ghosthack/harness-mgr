package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreflightDialogTest {

    private static final PreflightDialog.Session SESSION =
            new PreflightDialog.Session("Readable name", "hm-project", "working");

    @Test
    void sessionLabelKeepsDisplayTmuxAndStateVisible() {
        assertEquals("Readable name (tmux: hm-project) — working", SESSION.label());
        assertEquals("hm-project — idle",
                new PreflightDialog.Session("hm-project", "hm-project", "idle").label());
    }

    @Test
    void everyPreflightExplainsWhatCannotBeRestoredOrResumed() {
        List<PreflightDialog.Request> requests = List.of(
                PreflightDialog.kill(SESSION),
                PreflightDialog.close(SESSION),
                PreflightDialog.closeAll(List.of(SESSION)),
                PreflightDialog.stopStimAndReattach(SESSION));

        for (PreflightDialog.Request request : requests) {
            String explanation = request.consequence().toLowerCase();
            assertTrue(explanation.contains("cannot") || explanation.contains("no longer"),
                    () -> request.title() + " does not explain the irreversible consequence");
        }
    }

    @Test
    void closeKeepsTheSavedEntryAvailableToResume() {
        assertTrue(PreflightDialog.close(SESSION).consequence().contains(
                "saved entry remains available to Resume"));
        assertTrue(PreflightDialog.closeAll(List.of(SESSION)).consequence().contains(
                "every saved entry remains available to Resume"));
    }

    @Test
    void closeAllNamesTheBlastRadiusAndSnapshotsItsSessions() {
        List<PreflightDialog.Session> sessions = new ArrayList<>();
        sessions.add(SESSION);
        sessions.add(new PreflightDialog.Session("Second", "hm-second", "idle"));

        PreflightDialog.Request request = PreflightDialog.closeAll(sessions);
        sessions.clear();

        assertEquals("Review the 2 live sessions before closing them.", request.prompt());
        assertEquals(2, request.sessions().size());
        assertEquals("Close all", request.confirmLabel());
    }

    @Test
    void shortReviewHasNoScrollbarAndLongReviewScrollsVerticallyOnly() {
        assertInstanceOf(JPanel.class, PreflightDialog.sessionReview(List.of(SESSION)));

        List<PreflightDialog.Session> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(new PreflightDialog.Session("Session " + i, "hm-" + i, "idle"));
        }
        JScrollPane scroll = assertInstanceOf(JScrollPane.class,
                PreflightDialog.sessionReview(many));
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                scroll.getHorizontalScrollBarPolicy());
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.getVerticalScrollBarPolicy());
    }
}
