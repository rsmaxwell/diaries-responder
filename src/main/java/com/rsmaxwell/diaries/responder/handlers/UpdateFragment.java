package com.rsmaxwell.diaries.responder.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
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
import com.rsmaxwell.mqtt.rpc.utilities.Conflict;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		if (claims == null) {
			log.info("UpdateFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdateFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment incomingFragment;
		Fragment originalFragment;

		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			String text = Utilities.getString(args, "text");

			// get the lock fields
			Long lockUserId = claims.get("userId", Long.class);
			String lockUsername = claims.get("username", String.class);
			String lockKnownAs = claims.get("knownAs", String.class);
			String lockSessionId = claims.get("sessionId", String.class);

			// (1) load original from DB (includes current lock state)
			originalFragment = context.inflateFragment(id);

			// (2) enforce: must own the lock
			LockInfo originalLock = originalFragment.getLock();
			if (originalLock == null || !originalLock.isLocked()) {
				throw new BadRequest("Fragment is not locked");
			}
			if (!originalLock.isLockedBy(lockUserId, lockSessionId)) {
				throw new Conflict("Fragment is locked by another session");
			}

			// (3) build incoming fragment WITHOUT taking lock fields from client
			// @formatter:off
		    FragmentDBDTO fragmentDBDTO = FragmentDBDTO.builder()
		        .id(id)
		        .version(version)
		        .year(year)
		        .month(month)
		        .day(day)
		        .sequence(sequence)
		        .text(text)
		        .lock(originalLock) // carry lock forward so we can clear it after version bump
		        .build();
			// @formatter:on			

			// (4) check and bump the version
			incomingFragment = new Fragment(fragmentDBDTO);
			incomingFragment.checkAndIncrementVersion(originalFragment);

			// (5) release the lock after successful update
			incomingFragment.setLock(null); // simplest: DB columns become NULL

			// (6) save to database
			int count = fragmentRepository.update(incomingFragment);
			if (count != 1) {
				log.info("UpdateFragment.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (BadRequest e) {
			tx.rollback();
			return Response.badRequest(e.getMessage());
		} catch (Conflict e) {
			tx.rollback();
			return Response.conflict(e.getMessage());
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// (7) get the marquee associated with the fragment (can be null)
		Marquee marquee = null;
		Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(incomingFragment);
		if (optionalMarqueeDTO.isPresent()) {
			MarqueeDBDTO marqueeDTO = optionalMarqueeDTO.get();
			marquee = context.inflateMarquee(marqueeDTO);
		}

		// (8) If the fragment keys have changed, then remove the fragment from the topicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalFragment.keyFieldsChanged(incomingFragment)) {
			log.info("UpdateFragment.handleRequest: removing the original fragment from the TopicTree");
			FragmentPublishDTO dto = new FragmentPublishDTO(originalFragment, marquee);
			dto.remove(client);
		}

		// (9) publish the Fragment to the topic tree
		log.info("UpdateFragment.handleRequest: publishing the incoming fragment to the TopicTree");
		FragmentPublishDTO dto = new FragmentPublishDTO(incomingFragment, marquee);
		dto.publish(client);

		return Response.success(incomingFragment.getId());
	}
}
