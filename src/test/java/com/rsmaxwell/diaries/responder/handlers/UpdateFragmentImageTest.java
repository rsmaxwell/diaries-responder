package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.rsmaxwell.diaries.responder.model.*;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class UpdateFragmentImageTest {
    Fragment fragment(FragmentType type, Long imageId) {
        return Fragment.builder().type(type).persistedPageId(22L).persistedImageId(imageId)
                .year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Text").build();
    }
    final DiaryContext context = new DiaryContext() {
        @Override public Image lockImageForFragmentWrite(Long id) {
            if (id == null) return null;
            if (id == 999) throw new IllegalArgumentException("not found");
            return Image.builder().id(id).build();
        }
    };
    @org.junit.jupiter.api.BeforeEach void enableAuthoring() {
        var config = new com.rsmaxwell.diaries.responder.config.Config(); config.setImageFragmentWritesEnabled(true); context.setConfig(config);
    }
    @Test void disabledGateAllowsPreservationButRejectsAttachReplaceAndClear() throws Exception {
        context.getConfig().setImageFragmentWritesEnabled(false);
        var selected=fragment(FragmentType.IMAGE,91L);
        UpdateFragment.applyImageSelection(context,Map.of(),selected);
        UpdateFragment.applyImageSelection(context,Map.of("imageId",91),selected);
        Map<String,Object> clear=new HashMap<>();clear.put("imageId",null);
        for(var args:List.of(clear,Map.<String,Object>of("imageId",92))) {
            assertEquals(403,assertThrows(RpcStatusException.class,()->UpdateFragment.applyImageSelection(context,args,selected)).getStatus().code());
            assertEquals(91L,selected.getImageId());
        }
        var empty=fragment(FragmentType.IMAGE,null);
        UpdateFragment.applyImageSelection(context,clear,empty);
        assertEquals(403,assertThrows(RpcStatusException.class,()->UpdateFragment.applyImageSelection(context,Map.of("imageId",91),empty)).getStatus().code());
        assertNull(empty.getImageId());
    }
    @Test void absentPreservesNullClearsAndValueReplaces() throws Exception {
        var f=fragment(FragmentType.IMAGE,91L);
        UpdateFragment.applyImageSelection(context,Map.of(),f);assertEquals(91L,f.getImageId());
        UpdateFragment.applyImageSelection(context,Map.of("imageId",92L),f);assertEquals(92L,f.getImageId());
        Map<String,Object> clear=new HashMap<>();clear.put("imageId",null);
        UpdateFragment.applyImageSelection(context,clear,f);assertNull(f.getImageId());
        UpdateFragment.applyImageSelection(context,Map.of("imageId",91L),f);assertEquals(91L,f.getImageId());
    }
    @Test void marqueeAndLegacyNullCannotAcquireImages() throws Exception {
        for(var type:new FragmentType[]{FragmentType.MARQUEE,null}) {
            var f=fragment(type,null);
            UpdateFragment.applyImageSelection(context,Map.of(),f);
            Map<String,Object> clear=new HashMap<>();clear.put("imageId",null);
            UpdateFragment.applyImageSelection(context,clear,f);
            assertThrows(RpcStatusException.class,()->UpdateFragment.applyImageSelection(context,Map.of("imageId",91),f));
        }
    }
    @Test void invalidOrMissingImagesAreControlledFailures() {
        var f=fragment(FragmentType.IMAGE,91L);
        for(Object id:List.of(-1,0,1.5,"bad",true,999L,"9223372036854775808")) {
            var error=assertThrows(RpcStatusException.class,()->UpdateFragment.applyImageSelection(context,Map.of("imageId",id),f));
            assertEquals(400,error.getStatus().code());assertEquals(91L,f.getImageId());
        }
    }
    @Test void identityKeysMayConfirmButCannotChangeOwnership() throws Exception {
        var f=fragment(FragmentType.IMAGE,91L);
        UpdateFragment.requireUnchangedIdentity(Map.of("pageId",22,"type","IMAGE"),f);
        for(var args:List.of(Map.<String,Object>of("pageId",23),Map.<String,Object>of("type","MARQUEE"),Map.<String,Object>of("pageId",22.5)))
            assertThrows(RpcStatusException.class,()->UpdateFragment.requireUnchangedIdentity(args,f));
        Map<String,Object> clear=new HashMap<>();clear.put("type",null);
        assertThrows(RpcStatusException.class,()->UpdateFragment.requireUnchangedIdentity(clear,f));
    }
}
