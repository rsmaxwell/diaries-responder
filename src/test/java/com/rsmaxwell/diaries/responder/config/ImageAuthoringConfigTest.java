package com.rsmaxwell.diaries.responder.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;

class ImageAuthoringConfigTest {
    @Test void newAuthoringConfigurationHasNoDeploymentGates() throws Exception {
        var mapper = new ObjectMapper();
        var config = mapper.readValue("{}", Config.class);
        assertNotNull(config);
        var declared = java.util.Arrays.stream(Config.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertFalse(declared.contains("imageFragmentWritesEnabled"));
        assertFalse(declared.contains("imageFragmentExtensionsWritesEnabled"));
    }

    @Test void serviceRejectsMalformedImageCreationWithoutAConfigurationGate() {
        var context = new DiaryContext();
        context.setConfig(null);
        assertThrows(IllegalArgumentException.class, () -> context.saveImageFragment(null));
    }

    @Test void retiredPropertiesMustBeRemovedFromLocalJson() {
        var mapper = new ObjectMapper();
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"imageFragmentWritesEnabled\":true}", Config.class));
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"imageFragmentExtensionsWritesEnabled\":true}", Config.class));
    }
}
