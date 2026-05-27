package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentAndMarquee;
import com.rsmaxwell.diaries.responder.utilities.FragmentLocking;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UnlockFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(UnlockFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UnlockFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("UnlockFragment.handleRequest: Authorization.check: OK!");

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		FragmentAndMarquee fragmentAndMarquee;

		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			Fragment fragment = context.inflateFragment(id);

			FragmentLocking.requireUnlockAllowed(fragment, claims);

			fragmentAndMarquee = FragmentLocking.clearLockInCurrentTransaction(context, fragment);

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("UnlockFragment.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		} catch (Exception e) {
			log.error("UnlockFragment.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		}

		// (6) publish the unlocked Fragment to the topic tree
		log.info("UnlockFragment.handleRequest: publishing the unlocked fragment to the TopicTree");
		FragmentLocking.publish(context, fragmentAndMarquee);

		return Response.success(fragmentAndMarquee.getFragment().getId());
	}
}
