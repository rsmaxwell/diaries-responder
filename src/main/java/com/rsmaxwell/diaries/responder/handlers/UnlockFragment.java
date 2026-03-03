package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UnlockFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UnlockFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UnlockFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		if (claims == null) {
			log.info("UnlockFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UnlockFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment fragment;

		tx.begin();
		try {

			// get the incoming Fragment ID and inflate it
			Long id = Utilities.getLong(args, "id");
			fragment = context.inflateFragment(id);

			// get the fields needed to check the lock
			Long userId = claims.get("userId", Long.class);
			String sessionId = claims.get("sessionId", String.class);

			// Ensure lock object exists
			LockInfo lock = fragment.getLock();
			if (lock == null) {
				lock = new LockInfo();
				fragment.setLock(lock);
			}

			// Prevent stealing someone else’s lock
			if (lock.isLocked() && !lock.isLockedBy(userId, sessionId)) {
				tx.rollback();
				return Response.conflict("Fragment is locked by another user.");
			}

			// Unlock the fragment
			lock.clear();

			// save to database
			int count = fragmentRepository.update(fragment);
			if (count != 1) {
				log.info("UnlockFragment.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (BadRequest e) {
			tx.rollback();
			return Response.badRequest(e.getMessage());
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// get the marquee associated with the fragment (can be null)
		Marquee marquee = null;
		Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(fragment);
		if (optionalMarqueeDTO.isPresent()) {
			MarqueeDBDTO marqueeDTO = optionalMarqueeDTO.get();
			marquee = context.inflateMarquee(marqueeDTO);
		}

		// (6) publish the unlocked Fragment to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		log.info("UnlockFragment.handleRequest: publishing the unlocked fragment to the TopicTree");
		FragmentPublishDTO dto = new FragmentPublishDTO(fragment, marquee);
		dto.publish(client);

		return Response.success(fragment.getId());
	}
}
