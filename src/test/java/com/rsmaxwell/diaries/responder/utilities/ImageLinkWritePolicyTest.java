package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class ImageLinkWritePolicyTest {
    private DiaryContext context(boolean extensions) {
        DiaryContext ctx = new DiaryContext();
        Config config = new Config();
        config.setImageFragmentWritesEnabled(true);
        config.setImageFragmentExtensionsWritesEnabled(extensions);
        ctx.setConfig(config);
        return ctx;
    }
    private Fragment image() { Fragment f = new Fragment(); f.setType(FragmentType.IMAGE); return f; }

    @Test void preserveSetClearAndExplicitCombinedNullImageDoNotLoseLink() throws Exception {
        Fragment f=image();
        String url="https://example.invalid/map?x=a%20b&y=2#point";
        ImageLinkWritePolicy.apply(context(true), Map.of("imageLinkUrl",url), f);
        assertEquals(url,f.getImageLinkUrl());
        ImageLinkWritePolicy.apply(context(false),Map.of(),f);
        assertEquals(url,f.getImageLinkUrl());
        Map<String,Object> combined=new HashMap<>();
        combined.put("imageId",null);combined.put("clearImageLinkUrl",true);
        ImageLinkWritePolicy.apply(context(true),combined,f);
        assertNull(f.getImageLinkUrl());
    }

    @Test void rejectsBadFormsWithoutMutation() throws Exception {
        Fragment f=image();f.setImageLinkUrl("https://good.invalid/");
        for(String bad:List.of("", "//example.invalid/path", "/local", "javascript:alert(1)",
                "data:text/html,foo", "ftp://example.invalid/a", "http:///missing-host",
                "http://user:pass@example.invalid/a", "https://example.invalid/%ZZ",
                "https://example.invalid/has space", "https://example.invalid/\n",
                "https://example.invalid/"+"a".repeat(4096))) {
            assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(context(true),Map.of("imageLinkUrl",bad),f), bad);
            assertEquals("https://good.invalid/",f.getImageLinkUrl());
        }
        for(Map<String,Object> bad:List.of(Map.<String,Object>of("clearImageLinkUrl",false),
                Map.<String,Object>of("clearImageLinkUrl","true"),
                Map.<String,Object>of("clearImageLinkUrl",true,"imageLinkUrl","https://good.invalid/"))) {
            assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(context(true),bad,f));
            assertEquals("https://good.invalid/",f.getImageLinkUrl());
        }
        Map<String,Object> nullSet=new HashMap<>();nullSet.put("imageLinkUrl",null);
        assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(context(true),nullSet,f));
    }

    @Test void disabledExtensionGateRejectsDeliberateWriteButNotPreservation() throws Exception {
        Fragment f=image();f.setImageLinkUrl("http://example.invalid/old");
        ImageLinkWritePolicy.apply(context(false),Map.of(),f);
        assertEquals("http://example.invalid/old",f.getImageLinkUrl());
        assertEquals(403,assertThrows(RpcStatusException.class,()->
                ImageLinkWritePolicy.apply(context(false),Map.of("clearImageLinkUrl",true),f)).getStatus().code());
        assertEquals("http://example.invalid/old",f.getImageLinkUrl());
    }

    @Test void nonImageCannotAcquireLinkAndValidUrlPreservesEncodedPath() throws Exception {
        Fragment f=new Fragment(); f.setType(FragmentType.MARQUEE);
        assertThrows(RpcStatusException.class,()->ImageLinkWritePolicy.apply(context(true),
                Map.of("imageLinkUrl","https://good.invalid"),f));
        assertEquals("http://example.invalid/a?x=1&y=%C3%A9#z",
                ImageLinkWritePolicy.validate("http://example.invalid/a?x=1&y=%C3%A9#z"));
    }
}
