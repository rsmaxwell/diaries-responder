package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentLocking;
import com.rsmaxwell.diaries.responder.utilities.ImageFragmentExtensionsWritePolicy;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/** 0051: supports a source-page Marquee on IMAGE without changing Fragment type. */
public class AddMarquee extends RequestHandler {
    private static final double MIN_MARQUEE_SIZE = 40.0;

    @Override
    public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
        DiaryContext context = (DiaryContext) ctx;
        Claims claims = Authorization.checkToken(context, "access", Authorization.getAccessToken(userProperties));
        Authorization.checkActive(claims);
        Authorization.checkRoleAtLeast(claims, Role.EDITOR);

        Long pageId = Utilities.getLong(args, "pageId");
        Long fragmentId = Utilities.getLong(args, "fragmentId");
        Double x = Utilities.getDouble(args, "x");
        Double y = Utilities.getDouble(args, "y");
        Double width = Math.max(Utilities.getDouble(args, "width"), MIN_MARQUEE_SIZE);
        Double height = Math.max(Utilities.getDouble(args, "height"), MIN_MARQUEE_SIZE);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height))
            throw RpcStatusException.badRequest("Invalid Marquee geometry");

        EntityManager em = context.getEntityManager();
        EntityTransaction tx = em.getTransaction();
        if (tx.isActive()) throw new IllegalStateException("AddMarquee owns its transaction");
        Fragment fragment;
        Page page;
        Marquee savedMarquee;
        tx.begin();
        try {
            // Prevent concurrent attach/delete/version updates bypassing uniqueness checks.
            if (em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
                    .setParameter("id", fragmentId).getResultList().isEmpty())
                throw RpcStatusException.badRequest("Fragment not found");
            fragment = context.inflateFragment(fragmentId);
            page = context.inflatePage(pageId);
            FragmentLocking.requireMarqueeCompatible(fragment);
            if (context.getMarqueeRepository().findByFragment(fragment).isPresent())
                throw RpcStatusException.conflict("Fragment already has a Marquee");
            if (fragment.getPageId() != null && !fragment.getPageId().equals(pageId))
                throw RpcStatusException.badRequest("Marquee and Fragment must belong to the same Page");

            if (fragment.getType() == FragmentType.IMAGE) {
                ImageFragmentExtensionsWritePolicy.requireEnabled(context);
                FragmentLocking.requireLockedByCaller(fragment, claims);
                if (!args.containsKey("fragmentVersion") ||
                        !fragment.getVersion().equals(Utilities.getLong(args, "fragmentVersion")))
                    throw RpcStatusException.conflict("IMAGE Fragment version changed before Marquee attach");
            } else if (args.containsKey("fragmentVersion")) {
                // Optional old-type version check: no change to legacy callers that omit it.
                if (!fragment.getVersion().equals(Utilities.getLong(args, "fragmentVersion")))
                    throw RpcStatusException.conflict("Fragment version changed before Marquee attach");
            }
            savedMarquee = Marquee.builder().page(page).fragment(fragment)
                    .x(x).y(y).width(width).height(height).version(0L).build();
            // Existing MARQUEE compatibility: a legacy nullable Page/Fragment type can be repaired.
            if (fragment.getType() != FragmentType.IMAGE) {
                if (fragment.getPageId() == null) fragment.setPage(page);
                fragment.setType(FragmentType.MARQUEE);
            } else {
                // 0051: no IMAGE identity, link, catalogue Image or geometry changes.
                new com.rsmaxwell.diaries.responder.utilities.ResolvedFragmentState(fragment, savedMarquee).validateForWrite();
                fragment.incrementVersion();
                fragment.setLock(null);
            }
            if (context.getFragmentRepository().update(fragment) != 1)
                throw RpcStatusException.conflict("Fragment changed before Marquee attach");
            Long marqueeId = context.getMarqueeRepository().save(savedMarquee);
            savedMarquee.setId(marqueeId);
            tx.commit();
        } catch (Exception failure) {
            if (tx.isActive()) tx.rollback();
            if (failure instanceof RpcStatusException status) throw status;
            throw RpcStatusException.internalError("Unable to add Marquee");
        }

        // Publish both objects only after commit.
        MqttAsyncClient client = context.getPublisherClient();
        new FragmentPublishDTO(fragment, savedMarquee).publish(client);
        MarqueePublishDTO dto = new MarqueePublishDTO(savedMarquee);
        dto.publish(client, page.getDiary().getId());
        return Response.success(dto);
    }
}
