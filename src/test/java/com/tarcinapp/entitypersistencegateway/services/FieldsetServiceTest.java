package com.tarcinapp.entitypersistencegateway.services;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetDefinition;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetMode;

class FieldsetServiceTest {

    private FieldsetService fieldsetService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        fieldsetService = new FieldsetService();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testHideMode_SimpleFields() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"name\":\"John\",\"email\":\"john@example.com\",\"password\":\"secret\"}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.HIDE);
        fieldset.setFields(Arrays.asList("password", "email"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        assertTrue(resultMap.containsKey("id"));
        assertTrue(resultMap.containsKey("name"));
        assertFalse(resultMap.containsKey("password"));
        assertFalse(resultMap.containsKey("email"));
    }

    @Test
    void testHideMode_NestedFields() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"user\":{\"name\":\"John\",\"email\":\"john@example.com\",\"password\":\"secret\"}}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.HIDE);
        fieldset.setFields(Arrays.asList("user.password", "user.email"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        assertTrue(resultMap.containsKey("id"));
        assertTrue(resultMap.containsKey("user"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) resultMap.get("user");
        assertTrue(userMap.containsKey("name"));
        assertFalse(userMap.containsKey("password"));
        assertFalse(userMap.containsKey("email"));
    }

    @Test
    void testHideMode_ArrayFields() throws JsonProcessingException {
        String payload = "[{\"id\":\"1\",\"name\":\"John\",\"password\":\"secret1\"},{\"id\":\"2\",\"name\":\"Jane\",\"password\":\"secret2\"}]";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.HIDE);
        fieldset.setFields(Arrays.asList("password"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        // Note: For arrays at root level in hide mode with JsonPath, 
        // we need to use the array notation
        assertTrue(result.contains("\"name\""));
        assertFalse(result.contains("\"password\""));
    }

    @Test
    void testShowMode_SimpleFields() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"name\":\"John\",\"email\":\"john@example.com\",\"password\":\"secret\",\"role\":\"admin\"}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.SHOW);
        fieldset.setFields(Arrays.asList("id", "name"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        assertTrue(resultMap.containsKey("id"));
        assertTrue(resultMap.containsKey("name"));
        assertFalse(resultMap.containsKey("email"));
        assertFalse(resultMap.containsKey("password"));
        assertFalse(resultMap.containsKey("role"));
        assertEquals(2, resultMap.size());
    }

    @Test
    void testShowMode_NestedFields() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"user\":{\"name\":\"John\",\"email\":\"john@example.com\",\"password\":\"secret\"}}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.SHOW);
        fieldset.setFields(Arrays.asList("id", "user.name"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        assertTrue(resultMap.containsKey("id"));
        assertTrue(resultMap.containsKey("user"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) resultMap.get("user");
        assertTrue(userMap.containsKey("name"));
        assertFalse(userMap.containsKey("email"));
        assertFalse(userMap.containsKey("password"));
        assertEquals(1, userMap.size());
    }

    @Test
    void testShowMode_ArrayFields() throws JsonProcessingException {
        String payload = "[{\"id\":\"1\",\"name\":\"John\",\"email\":\"john@example.com\"},{\"id\":\"2\",\"name\":\"Jane\",\"email\":\"jane@example.com\"}]";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.SHOW);
        fieldset.setFields(Arrays.asList("id", "name"));
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        assertTrue(result.contains("\"id\""));
        assertTrue(result.contains("\"name\""));
        assertFalse(result.contains("\"email\""));
    }

    @Test
    void testEmptyFieldset_ReturnsOriginal() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"name\":\"John\"}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.HIDE);
        fieldset.setFields(Arrays.asList());
        
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        assertEquals(payload, result);
    }

    @Test
    void testNullFieldset_ReturnsOriginal() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"name\":\"John\"}";
        
        String result = fieldsetService.applyFieldset(payload, null);
        
        assertEquals(payload, result);
    }

    @Test
    void testHideMode_NonExistentField_NoError() throws JsonProcessingException {
        String payload = "{\"id\":\"123\",\"name\":\"John\"}";
        
        FieldsetDefinition fieldset = new FieldsetDefinition();
        fieldset.setMode(FieldsetMode.HIDE);
        fieldset.setFields(Arrays.asList("nonexistent", "alsoMissing"));
        
        // Should not throw exception
        String result = fieldsetService.applyFieldset(payload, fieldset);
        
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        assertTrue(resultMap.containsKey("id"));
        assertTrue(resultMap.containsKey("name"));
    }
}
