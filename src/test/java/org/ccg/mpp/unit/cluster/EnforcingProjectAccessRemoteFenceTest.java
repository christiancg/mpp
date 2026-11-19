package org.ccg.mpp.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.UUID;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class EnforcingProjectAccessRemoteFenceTest extends RemoteOwnerTxTestBase {
    private static final String ADMIN = "remote-fence-admin";

    @Override
    String collectionPrefix() {
        return "remote-fence-";
    }

    @Override
    void beforeClusterStart() {
        TestUtils.createAdmin(ADMIN);
    }

    private EnforcingProjectAccess scriptThatSavedRemotely(UUID scriptClientId, String id) throws Exception {
        final var project = new EnforcingProjectAccess(ADMIN, scriptClientId);
        project.beginTransaction();
        project.save(TestGlobals.PROJECT, coll, document(id));
        makeTheOwnerTheOnlyNode();
        return project;
    }

    private static void commitOnAnEdgeSharingNoDiskWithTheOwner(EnforcingProjectAccess project) {
        try (var edgeMarkers = Mockito.mockStatic(org.ccg.mpp.ops.tx.SliceStateMachine.class,
                Mockito.CALLS_REAL_METHODS)) {
            edgeMarkers.when(() -> org.ccg.mpp.ops.tx.SliceStateMachine.isFenced(anyString())).thenReturn(false);
            assertThrows(JsThrowException.class, project::commitTransaction);
        }
    }

    private void assertTheSliceIsStillReachable(UUID edgeClientId) {
        assertNotNull(clientTracker.getActiveTransaction(edgeClientId),
                "the owner still holds the slice, so the edge must keep the transaction");
        assertFalse(clientTracker.transactionParticipants(edgeClientId).isEmpty(),
                "a re-sent COMMIT can only reach the owner through the recorded participant");
    }

    @Test
    public void a_script_commit_the_owner_fenced_keeps_the_edge_transaction_and_its_participant() throws Exception {
        final var project = scriptThatSavedRemotely(clientId, "fenced");
        removeCollectionFolder();

        commitOnAnEdgeSharingNoDiskWithTheOwner(project);

        assertTrue(project.lastCommitWasFenced());
        assertTheSliceIsStillReachable(clientId);
    }

    @Test
    public void a_resent_wire_commit_then_finishes_the_slice() throws Exception {
        final var project = scriptThatSavedRemotely(clientId, "finished");
        removeCollectionFolder();
        commitOnAnEdgeSharingNoDiskWithTheOwner(project);

        fs.createCollectionFile(TestGlobals.PROJECT, coll);
        final var commit = new CommitTransactionRequest();
        final var resent = router.forward(commit, eJson.toJson(commit), true, ADMIN, clientId);

        assertNull(eJson.fromJson(resent, OperationResponse.class).getErrorCode(),
                "the re-sent COMMIT must finish the slice, got: " + resent);
        assertEquals(OperationStatus.OK, findStatus("finished"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void an_ordinary_script_commit_to_a_sole_owner_still_clears_the_edge() throws Exception {
        final var project = scriptThatSavedRemotely(clientId, "ordinary");

        assertDoesNotThrow(project::commitTransaction);

        assertFalse(project.lastCommitWasFenced());
        assertEquals(OperationStatus.OK, findStatus("ordinary"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
    }

    @Test
    public void a_script_on_a_transient_client_keeps_the_fenced_session() throws Exception {
        final var project = new EnforcingProjectAccess(ADMIN, null);
        project.beginTransaction();
        final var sessionClientId = TestUtils.getPrivateField(project, "sessionClientId", UUID.class);
        project.save(TestGlobals.PROJECT, coll, document("transient"));
        makeTheOwnerTheOnlyNode();
        removeCollectionFolder();

        commitOnAnEdgeSharingNoDiskWithTheOwner(project);

        assertTrue(project.lastCommitWasFenced());
        assertTheSliceIsStillReachable(sessionClientId);
        clientTracker.removeById(sessionClientId);
    }
}
