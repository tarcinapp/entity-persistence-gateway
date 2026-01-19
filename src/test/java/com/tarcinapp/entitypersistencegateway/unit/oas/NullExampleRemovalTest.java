package com.tarcinapp.entitypersistencegateway.unit.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that null examples are completely removed from serialized OAS.
 */
@DisplayName("Null Example Removal Tests")
class NullExampleRemovalTest {
    
    @Test
    @DisplayName("Null examples should not appear in Jackson-serialized output")
    void testNullExamplesRemovedBySerialization() throws Exception {
        // Create an OpenAPI spec with a response that has example: null
        OpenAPI openApi = new OpenAPI();
        
        PathItem pathItem = new PathItem();
        Operation getOp = new Operation();
        
        ApiResponses responses = new ApiResponses();
        ApiResponse response200 = new ApiResponse();
        response200.setDescription("Success");
        
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setExample(null); // This is the problem: example field is null
        content.addMediaType("application/json", mediaType);
        response200.setContent(content);
        
        responses.addApiResponse("200", response200);
        getOp.setResponses(responses);
        
        pathItem.setGet(getOp);
        
        Paths paths = new Paths();
        paths.addPathItem("/test", pathItem);
        openApi.setPaths(paths);
        
        // Step 1: Serialize with a normal ObjectMapper (WITH nulls)
        ObjectMapper normalMapper = new ObjectMapper();
        String normalJson = normalMapper.writeValueAsString(openApi);
        assertTrue(normalJson.contains("example"), "Normal mapper should include example field");
        assertTrue(normalJson.contains("null"), "Normal mapper should include null values");
        
        // Step 2: Serialize with NON_NULL mapper (WITHOUT nulls)
        ObjectMapper cleanMapper = new ObjectMapper();
        cleanMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String cleanJson = cleanMapper.writeValueAsString(openApi);
        
        // CRITICAL: "example":null should NOT appear in clean output
        assertFalse(cleanJson.contains("\"example\":null"), 
            "Clean JSON should NOT contain 'example:null' (strict match)");
        assertFalse(cleanJson.contains("example: null"), 
            "Clean JSON should NOT contain 'example: null' (YAML match)");
        
        // Step 3: Deserialize and re-serialize to verify complete cleanup
        OpenAPI deserializedApi = cleanMapper.readValue(cleanJson, OpenAPI.class);
        String reserializedJson = cleanMapper.writeValueAsString(deserializedApi);
        
        assertFalse(reserializedJson.contains("\"example\":null"),
            "Re-serialized JSON should NOT contain 'example:null'");
        
        System.out.println("✓ Null examples successfully removed from serialization");
        System.out.println("Normal JSON length: " + normalJson.length());
        System.out.println("Clean JSON length: " + cleanJson.length());
        System.out.println("Size reduction: " + (normalJson.length() - cleanJson.length()) + " bytes");
    }
    
    @Test
    @DisplayName("Full pipeline: OpenAPI with many null examples should serialize cleanly")
    void testFullPipelineNullRemoval() throws Exception {
        // Create a more realistic OpenAPI with multiple null examples
        OpenAPI openApi = new OpenAPI();
        
        Paths paths = new Paths();
        
        // Path 1: POST with request body example: null
        PathItem path1 = new PathItem();
        Operation post = new Operation();
        
        io.swagger.v3.oas.models.parameters.RequestBody requestBody = 
            new io.swagger.v3.oas.models.parameters.RequestBody();
        Content requestContent = new Content();
        MediaType requestMediaType = new MediaType();
        requestMediaType.setExample(null); // null example in request
        requestContent.addMediaType("application/json", requestMediaType);
        requestBody.setContent(requestContent);
        post.setRequestBody(requestBody);
        
        // Response with null example
        ApiResponses postResponses = new ApiResponses();
        ApiResponse postResp = new ApiResponse();
        postResp.setDescription("Created");
        Content postRespContent = new Content();
        MediaType postRespMedia = new MediaType();
        postRespMedia.setExample(null); // null example in response
        postRespContent.addMediaType("application/json", postRespMedia);
        postResp.setContent(postRespContent);
        postResponses.addApiResponse("201", postResp);
        post.setResponses(postResponses);
        
        path1.setPost(post);
        paths.addPathItem("/resources", path1);
        
        // Path 2: GET with null example
        PathItem path2 = new PathItem();
        Operation get = new Operation();
        ApiResponses getResponses = new ApiResponses();
        ApiResponse getResp = new ApiResponse();
        getResp.setDescription("OK");
        Content getRespContent = new Content();
        MediaType getRespMedia = new MediaType();
        getRespMedia.setExample(null); // null example
        getRespContent.addMediaType("application/json", getRespMedia);
        getResp.setContent(getRespContent);
        getResponses.addApiResponse("200", getResp);
        get.setResponses(getResponses);
        path2.setGet(get);
        paths.addPathItem("/resources/{id}", path2);
        
        openApi.setPaths(paths);
        
        // Serialize with NON_NULL mapper
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(openApi);
        
        // Count occurrences of null examples (should be 0)
        long nullExampleCount = json.split("\"example\":null").length - 1;
        assertEquals(0, nullExampleCount, "Should have 0 'example:null' occurrences in JSON");
        
        // Also check YAML style
        assertFalse(json.contains("example: null"), "Should not contain YAML-style 'example: null'");
        
        System.out.println("✓ Full pipeline: " + nullExampleCount + " null examples found (expected 0)");
    }
}
