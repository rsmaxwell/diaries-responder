package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentSequenceNormaliser;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class DeleteFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DeleteFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("DeleteFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment fragment = null;
		Marquee marquee = null;
		List<Fragment> normalisedFragments = List.of();

		if (tx.isActive()) throw new IllegalStateException("DeleteFragment owns its transaction");
		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			if (em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
					.setParameter("id", id).getResultList().isEmpty())
				throw RpcStatusException.badRequest("Fragment not found");
			var state = context.resolveFragmentState(context.inflateFragment(id));
			fragment = state.getFragment();
			marquee = state.getMarquee();
			if (fragment.getType() == com.rsmaxwell.diaries.responder.model.FragmentType.IMAGE && marquee != null)
				throw RpcStatusException.conflict("Remove the invalid Marquee with deleteMarquee before deleting this IMAGE Fragment");
			if (marquee != null && marqueeRepository.delete(marquee) != 1)
				throw RpcStatusException.conflict("Marquee changed during deletion");
			if (context.deleteFragment(fragment) != 1)
				throw RpcStatusException.conflict("Fragment changed during deletion");

			// Deletion changes the chronology just like a drag/drop move. Close the
			// resulting gap in the same transaction so the database never commits a
			// date that relies on startup normalisation to become contiguous again.
			normalisedFragments = FragmentSequenceNormaliser.normaliseDate(
					fragmentRepository,
					fragment.getYear(),
					fragment.getMonth(),
					fragment.getDay());

			tx.commit();
		} catch (Exception failure) {
			if (tx.isActive()) tx.rollback();
			if (failure instanceof RpcStatusException status) throw status;
			log.error("DeleteFragment failed", failure);
			throw RpcStatusException.internalError("Unable to delete Fragment");
		}

		MqttAsyncClient client = context.getPublisherClient();

		if (marquee != null) {
			Page page = marquee.getPage();
			MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(marquee);
			marqueePublishDTO.remove(client, page.getDiary().getId());
		}

		log.info("DeleteFragment.handleRequest: removing the fragment from the TopicTree");
		FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment, marquee);
		fragmentPublishDTO.remove(client);

		// Publish every committed survivor whose sequence/version changed. This keeps
		// canonical and date retained topics in sync immediately after the delete.
		log.info("DeleteFragment.handleRequest: publishing {} normalised survivor(s)", normalisedFragments.size());
		FragmentSequenceNormaliser.publish(context, normalisedFragments);

		return Response.success(fragment.getId());
	}
}
