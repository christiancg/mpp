package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.ccg.mpp.ops.resp.ListenResponse;
import org.ccg.mpp.ops.resp.StopListenResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ListenOperationTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final List<UUID> clients = new ArrayList<>();

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @AfterEach
    void unregisterEveryClient() {
        for (final var clientId : clients) {
            listenManager.unregisterAllForClient(clientId);
        }
    }

    private UUID client() {
        final var clientId = UUID.randomUUID();
        clients.add(clientId);
        return clientId;
    }

    private String registerListener(UUID clientId) {
        final var request = new ListenRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return ((ListenResponse) processor.processMessage(request, clientId)).getListenId();
    }

    private org.ccg.mpp.ops.resp.OperationResponse stopListen(String listenId, UUID clientId) {
        final var request = new StopListenRequest();
        request.setListenId(listenId);
        return processor.processMessage(request, clientId);
    }

    @Test
    public void processStopListen_fromAnotherClient_isRefused() {
        final var victim = client();
        final var attacker = client();
        final var listenId = registerListener(victim);

        final var stolen = stopListen(listenId, attacker);

        assertEquals(OperationStatus.NOT_FOUND, stolen.getStatus(),
                "any authenticated client could otherwise cancel a listener it does not own");
        assertEquals(org.ccg.mpp.ops.ErrorCode.LISTEN_NOT_FOUND.getCode(), stolen.getErrorCode(),
                "the refusal must be indistinguishable from an unknown id, or it is a membership oracle");
    }

    @Test
    public void processStopListen_leavesTheVictimRegistered() {
        final var victim = client();
        final var listenId = registerListener(victim);
        stopListen(listenId, client());

        assertInstanceOf(StopListenResponse.class, stopListen(listenId, victim),
                "the owner's registration must survive another client's attempt to cancel it");
    }

    @Test
    public void processStopListen_fromTheOwningClient_succeeds() {
        final var owner = client();
        final var listenId = registerListener(owner);

        assertInstanceOf(StopListenResponse.class, stopListen(listenId, owner));
        assertEquals(OperationStatus.NOT_FOUND, stopListen(listenId, owner).getStatus());
    }

    @Test
    public void processListen_existingCollection_returnsListenResponse() {
        final var req = new ListenRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        req.setAggregationSteps(List.of());

        final var resp = processor.processMessage(req, client());

        assertInstanceOf(ListenResponse.class, resp);
        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(((ListenResponse) resp).getListenId());
        assertNotNull(((ListenResponse) resp).getResultHash());
        assertNotNull(((ListenResponse) resp).getResults());
    }

    @Test
    public void processListen_withExistingDocument_returnsItInResults() {
        final var saveReq = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("listen-doc-1"));
        saveReq.setObject(obj);
        processor.processMessage(saveReq);

        final var req = new ListenRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        req.setAggregationSteps(List.of());

        final var resp = (ListenResponse) processor.processMessage(req, client());

        assertTrue(resp.getResults().stream()
                .anyMatch(r -> "listen-doc-1".equals(r.get("_id").asJsonString().getValue())));
    }

    @Test
    public void processStopListen_validId_returnsStopListenResponse() {
        final var clientId = client();
        final var listenReq = new ListenRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenResp.getListenId());
        final var stopResp = processor.processMessage(stopReq, clientId);

        assertInstanceOf(StopListenResponse.class, stopResp);
        assertEquals(OperationType.STOP_LISTEN, stopResp.getType());
        assertEquals(OperationStatus.OK, stopResp.getStatus());
    }

    @Test
    public void processStopListen_unknownId_returns404() {
        final var stopReq = new StopListenRequest();
        stopReq.setListenId(UUID.randomUUID().toString());

        final var resp = processor.processMessage(stopReq, client());

        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
        assertEquals("404-7", resp.getErrorCode());
    }

    @Test
    public void processStopListen_alreadyUnregistered_returns404() {
        final var clientId = client();
        final var listenReq = new ListenRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenResp.getListenId());
        processor.processMessage(stopReq, clientId);
        final var secondStop = processor.processMessage(stopReq, clientId);

        assertEquals(OperationStatus.NOT_FOUND, secondStop.getStatus());
    }

    @Test
    public void dropCollection_dropsActiveListeners() {
        final var dropColl = "listenDropColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, dropColl));

        final var clientId = client();
        final var listenReq = new ListenRequest(TestGlobals.PROJECT, dropColl);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);
        final var listenId = listenResp.getListenId();

        processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, dropColl));

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenId);
        final var stopResp = processor.processMessage(stopReq, clientId);

        assertEquals(OperationStatus.NOT_FOUND, stopResp.getStatus());
    }

    @Test
    public void dropProject_dropsActiveListeners() {
        final var dropProject = "listenDropProject";
        processor.processMessage(new CreateProjectRequest(dropProject));
        processor.processMessage(new CreateCollectionRequest(dropProject, "coll1"));
        processor.processMessage(new CreateCollectionRequest(dropProject, "coll2"));

        final var clientId = client();
        final var listenReq1 = new ListenRequest(dropProject, "coll1");
        listenReq1.setAggregationSteps(List.of());
        final var listenId1 = ((ListenResponse) processor.processMessage(listenReq1, clientId)).getListenId();

        final var listenReq2 = new ListenRequest(dropProject, "coll2");
        listenReq2.setAggregationSteps(List.of());
        final var listenId2 = ((ListenResponse) processor.processMessage(listenReq2, clientId)).getListenId();

        processor.processMessage(new DropProjectRequest(dropProject));

        final var stopReq1 = new StopListenRequest();
        stopReq1.setListenId(listenId1);
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(stopReq1, clientId).getStatus());

        final var stopReq2 = new StopListenRequest();
        stopReq2.setListenId(listenId2);
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(stopReq2, clientId).getStatus());
    }
}
