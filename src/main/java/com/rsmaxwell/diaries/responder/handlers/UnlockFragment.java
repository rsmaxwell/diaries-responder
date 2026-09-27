package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.ResolvedFragmentState;
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

		Long id = Utilities.getLong(args, "id");
		ResolvedFragmentState resolvedState;

		tx.begin();
		try {
			// Serialize with Image selection updates before loading a full Fragment row.
			em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
					.setParameter("id", id).getResultList();
			Optional<FragmentDBDTO> optionalFragmentDTO = context.getFragmentRepository().findById(id);
			if (optionalFragmentDTO.isEmpty()) {
				/*
				 * Unlock is deliberately idempotent.
				 *
				 * This can happen when the client locks a fragment, deletes it, and then a
				 * live-object subscription emits null for the removed retained topic. In that
				 * case there is no lock left to clear because the fragment row has already
				 * gone, so returning success is the least surprising behaviour.
				 */
				log.info("UnlockFragment.handleRequest: fragment {} does not exist; treating unlock as already complete", id);
				tx.commit();
				return Response.success(id);
			}

			Fragment fragment = context.inflateFragment(optionalFragmentDTO.get());

			FragmentLocking.requireUnlockAllowed(fragment, claims);

			resolvedState = FragmentLocking.clearLockInCurrentTransaction(context, fragment);

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
		FragmentLocking.publish(context, resolvedState);

		return Response.success(resolvedState.getFragment().getId());
	}
}
