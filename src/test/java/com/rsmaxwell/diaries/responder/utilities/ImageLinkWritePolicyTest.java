package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class ImageLinkWritePolicyTest {
    private Fragment image() { Fragment f = new Fragment(); f.setType(FragmentType.IMAGE); return f; }

    @Test void preserveSetClearAndExplicitCombinedNullImageDoNotLoseLink() throws Exception {
        Fragment f=image();
        String url="https://example.invalid/map?x=a%20b&y=2#point";
        ImageLinkWritePolicy.apply(Map.of("imageLinkUrl",url), f);
        assertEquals(url,f.getImageLinkUrl());
        ImageLinkWritePolicy.apply(Map.of(),f);
        assertEquals(url,f.getImageLinkUrl());
        Map<String,Object> combined=new HashMap<>();
        combined.put("imageId",null);combined.put("clearImageLinkUrl",true);
        ImageLinkWritePolicy.apply(combined,f);
        assertNull(f.getImageLinkUrl());
    }

    @Test void rejectsBadFormsWithoutMutation() throws Exception {
        Fragment f=image();f.setImageLinkUrl("https://good.invalid/");
        for(String bad:List.of("", "//example.invalid/path", "/local", "javascript:alert(1)",
                "data:text/html,foo", "ftp://example.invalid/a", "http:///missing-host",
                "http://user:pass@example.invalid/a", "https://example.invalid/%ZZ",
                "https://example.invalid/has space", "https://example.invalid/\n",
                "https://example.invalid/"+"a".repeat(4096))) {
            assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(Map.of("imageLinkUrl",bad),f), bad);
            assertEquals("https://good.invalid/",f.getImageLinkUrl());
        }
        for(Map<String,Object> bad:List.of(Map.<String,Object>of("clearImageLinkUrl",false),
                Map.<String,Object>of("clearImageLinkUrl","true"),
                Map.<String,Object>of("clearImageLinkUrl",true,"imageLinkUrl","https://good.invalid/"))) {
            assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(bad,f));
            assertEquals("https://good.invalid/",f.getImageLinkUrl());
        }
        Map<String,Object> nullSet=new HashMap<>();nullSet.put("imageLinkUrl",null);
        assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(nullSet,f));
    }

    @Test void deliberateClearAndOmissionWorkWithoutDeploymentGate() throws Exception {
        Fragment f=image();f.setImageLinkUrl("http://example.invalid/old");
        ImageLinkWritePolicy.apply(Map.of(),f);
        assertEquals("http://example.invalid/old",f.getImageLinkUrl());
        ImageLinkWritePolicy.apply(Map.of("clearImageLinkUrl",true),f);
        assertNull(f.getImageLinkUrl());
    }

    @Test void nonImageCannotAcquireLinkAndValidUrlPreservesEncodedPath() throws Exception {
        Fragment f=new Fragment(); f.setType(FragmentType.MARQUEE);
        assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(
                Map.of("imageLinkUrl","https://good.invalid"),f));
        assertEquals("http://example.invalid/a?x=1&y=%C3%A9#z",
                ImageLinkWritePolicy.validate("http://example.invalid/a?x=1&y=%C3%A9#z"));
    }
}
