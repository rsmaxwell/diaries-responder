package com.rsmaxwell.diaries.responder.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class ImageFragmentGateConfigTest {
    @Test void missingNullAndFalseAreDisabledAndOnlyExplicitTrueEnables() throws Exception {
        var mapper=new ObjectMapper();
        for(String json:new String[]{"{}","{\"imageFragmentWritesEnabled\":null}","{\"imageFragmentWritesEnabled\":false}"})
            assertFalse(mapper.readValue(json,Config.class).isImageFragmentWritesEnabled());
        assertTrue(mapper.readValue("{\"imageFragmentWritesEnabled\":true}",Config.class).isImageFragmentWritesEnabled());
    }
    @Test void serviceCreationIsAlsoGatedBeforeDatabaseAccess() {
        var context=new DiaryContext();context.setConfig(new Config());
        var error=assertThrows(RpcStatusException.class,()->context.saveImageFragment(null));
        assertEquals(403,error.getStatus().code());
    }
}
