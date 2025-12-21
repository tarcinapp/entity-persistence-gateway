package com.tarcinapp.entitypersistencegateway.util;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.tarcinapp.entitypersistencegateway.fixtures.EntityFixtures;
import com.tarcinapp.entitypersistencegateway.fixtures.PolicyResponseFixtures;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Utility class for setting up WireMock stubs for external services.
 * Provides pre-configured stubs for OPA and backend services.
 */
public class WireMockSetup {

    private final WireMockServer wireMockServer;

    public WireMockSetup(WireMockServer wireMockServer) {
        this.wireMockServer = wireMockServer;
    }

    // ==================== OPA Stubs ====================

    /**
     * Stub OPA to allow the policy request.
     */
    public void stubOpaAllowPolicy(String policyPath) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PolicyResponseFixtures.allowedPolicyJson())));
    }

    /**
     * Stub OPA to deny the policy request.
     */
    public void stubOpaDenyPolicy(String policyPath) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PolicyResponseFixtures.deniedPolicyJson())));
    }

    /**
     * Stub OPA to allow with forbidden fields.
     */
    public void stubOpaWithForbiddenFields(String policyPath, List<String> fields) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PolicyResponseFixtures.forbiddenFieldsPolicyJson(fields))));
    }

    /**
     * Stub OPA to timeout.
     */
    public void stubOpaTimeout(String policyPath) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(30000))); // 30 second delay
    }

    /**
     * Stub OPA to return an error.
     */
    public void stubOpaError(String policyPath) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\": \"Internal Server Error\"}")));
    }

    /**
     * Stub OPA to return 503 Service Unavailable.
     */
    public void stubOpaUnavailable(String policyPath) {
        wireMockServer.stubFor(post(urlPathEqualTo(policyPath))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"error\": \"Service Unavailable\"}")));
    }

    // ==================== Backend Stubs ====================

    /**
     * Stub backend to return a single entity.
     */
    public void stubBackendGetEntity(String entityId) {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.simpleEntityWithId(entityId))));
    }

    /**
     * Stub backend to return a complete entity with all fields.
     */
    public void stubBackendGetCompleteEntity(String entityId) {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.completeEntityWithId(entityId))));
    }

    /**
     * Stub backend to return 404 Not Found.
     */
    public void stubBackendGetEntityNotFound(String entityId) {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Not Found\"}")));
    }

    /**
     * Stub backend to return a list of entities.
     */
    public void stubBackendGetEntities() {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.entityList(5))));
    }

    /**
     * Stub backend to return a list of entities with count.
     */
    public void stubBackendGetEntitiesWithCount(int count) {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.entityList(count))));
    }

    /**
     * Stub backend for entity creation.
     */
    public void stubBackendCreateEntity() {
        wireMockServer.stubFor(post(urlPathEqualTo("/entities"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.completeEntity())));
    }

    /**
     * Stub backend for entity update.
     */
    public void stubBackendUpdateEntity(String entityId) {
        wireMockServer.stubFor(patch(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.completeEntityWithId(entityId))));
    }

    /**
     * Stub backend for entity replacement.
     */
    public void stubBackendReplaceEntity(String entityId) {
        wireMockServer.stubFor(put(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.completeEntityWithId(entityId))));
    }

    /**
     * Stub backend for entity deletion.
     */
    public void stubBackendDeleteEntity(String entityId) {
        wireMockServer.stubFor(delete(urlPathEqualTo("/entities/" + entityId))
                .willReturn(aResponse()
                        .withStatus(204)));
    }

    /**
     * Stub backend to timeout.
     */
    public void stubBackendTimeout() {
        wireMockServer.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(30000)));
    }

    /**
     * Stub backend to return an error.
     */
    public void stubBackendError() {
        wireMockServer.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\": \"Internal Server Error\"}")));
    }

    /**
     * Stub backend entity count endpoint.
     */
    public void stubBackendEntityCount(int count) {
        wireMockServer.stubFor(get(urlPathEqualTo("/entities/count"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"count\": " + count + "}")));
    }

    // ==================== List Stubs ====================

    /**
     * Stub backend to return lists.
     */
    public void stubBackendGetLists() {
        wireMockServer.stubFor(get(urlPathEqualTo("/lists"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EntityFixtures.listRecordList(5))));
    }

    // ==================== Utility Methods ====================

    /**
     * Reset all stubs.
     */
    public void reset() {
        wireMockServer.resetAll();
    }

    /**
     * Verify a request was made to the given path.
     */
    public void verifyGetRequestMade(String path) {
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(path)));
    }

    /**
     * Verify a POST request was made to the given path.
     */
    public void verifyPostRequestMade(String path) {
        wireMockServer.verify(postRequestedFor(urlPathEqualTo(path)));
    }

    /**
     * Verify no requests were made to the given path.
     */
    public void verifyNoRequestMade(String path) {
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(path)));
    }

    /**
     * Get the WireMock server instance.
     */
    public WireMockServer getServer() {
        return wireMockServer;
    }
}
