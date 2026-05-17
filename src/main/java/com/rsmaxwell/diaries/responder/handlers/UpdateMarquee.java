package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Diary;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateMarquee extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(UpdateMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateMarquee.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("UpdateMarquee.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Fragment incomingFragment;
		Fragment originalFragment;
		Marquee incomingMarquee;
		Marquee originalMarquee;

		// First update the marquee in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment lockedFragment = null;
		Marquee lockedFragmentMarquee = null;
		boolean callerOwnsFragmentLock = false;
		boolean fragmentUnlockedInTransaction = false;

		tx.begin();
		try {

			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			Long pageId = Utilities.getLong(args, "pageId");
			Long fragmentId = Utilities.getLong(args, "fragmentId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			// (1) get the original Marquee
			originalMarquee = context.inflateMarquee(id);

			// (2) get the fragment associated with the original marquee
			originalFragment = originalMarquee.getFragment();

			// (3) enforce: the fragment must be locked by this user/session
			Long lockUserId = claims.get("userId", Long.class);
			String lockSessionId = claims.get("sessionId", String.class);

			LockInfo originalLock = originalFragment.getLock();
			if (originalLock == null || !originalLock.isLocked()) {
				throw RpcStatusException.badRequest("Fragment is not locked");
			}
			if (!originalLock.isLockedBy(lockUserId, lockSessionId)) {
				throw RpcStatusException.conflict("Fragment is locked by another session");
			}

			lockedFragment = originalFragment;
			callerOwnsFragmentLock = true;

			// Do not allow this request to silently move the marquee to another fragment.
			if (!originalFragment.getId().equals(fragmentId)) {
				throw RpcStatusException.badRequest("Marquee does not belong to the supplied fragment");
			}

			// (4) get the incoming Marquee

			//@formatter:off
			MarqueeDBDTO marqueeDTO = MarqueeDBDTO.builder()
					.id(id)
					.version(version)
					.pageId(pageId)
					.x(x)
					.y(y)
					.width(width)
					.height(height)
					.build();
			//@formatter:on

			Page incomingPage = context.inflatePage(pageId);
			incomingFragment = context.inflateFragment(fragmentId);
			incomingMarquee = new Marquee(incomingPage, incomingFragment, marqueeDTO);

			// (5) check and bump the version
			incomingMarquee.checkAndIncrementVersion(originalMarquee);

			// (6) save the marquee to database
			int marqueeCount = marqueeRepository.update(incomingMarquee);
			if (marqueeCount != 1) {
				log.info("UpdateMarquee.handleRequest: number of marquee records updated: {}", marqueeCount);
			}

			// (7) release the fragment lock after successful marquee update
			originalFragment.setLock(null);

			int fragmentCount = fragmentRepository.update(originalFragment);
			if (fragmentCount != 1) {
				log.info("UpdateMarquee.handleRequest: number of fragment records updated: {}", fragmentCount);
			}

			fragmentUnlockedInTransaction = true;

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("UpdateMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}

			unlockFragmentAfterFailedMarqueeUpdate(context, fragmentRepository, marqueeRepository, lockedFragment, callerOwnsFragmentLock, fragmentUnlockedInTransaction);

			throw e;
		} catch (Exception e) {
			log.error("UpdateMarquee.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}

			unlockFragmentAfterFailedMarqueeUpdate(context, fragmentRepository, marqueeRepository, lockedFragment, callerOwnsFragmentLock, fragmentUnlockedInTransaction);

			throw e;
		}

		// (5) Remove the original Marquee from the TopicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalMarquee.keyFieldsChanged(incomingMarquee)) {
			log.info("UpdateMarquee.handleRequest: removing the original marquee from the TopicTree");
			Page page = originalMarquee.getPage();
			Diary diary = page.getDiary();
			MarqueePublishDTO dto = new MarqueePublishDTO(originalMarquee);
			dto.remove(client, diary.getId());
		}

		log.info("UpdateMarquee.handleRequest: publishing the unlocked fragment to the TopicTree");
		FragmentPublishDTO fragmentDto = new FragmentPublishDTO(originalFragment, incomingMarquee);
		fragmentDto.publish(client);

		// (6) publish the Marquee to the topic tree
		Page page = incomingMarquee.getPage();
		Diary diary = page.getDiary();
		MarqueePublishDTO dto = new MarqueePublishDTO(incomingMarquee);
		dto.publish(client, diary.getId());

		return Response.success(dto);
	}

	private void unlockFragmentAfterFailedMarqueeUpdate(DiaryContext context, FragmentRepository fragmentRepository, MarqueeRepository marqueeRepository, Fragment lockedFragment,
			boolean callerOwnsFragmentLock, boolean fragmentUnlockedInTransaction) {

		if (!callerOwnsFragmentLock || fragmentUnlockedInTransaction || lockedFragment == null) {
			return;
		}

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		try {
			tx.begin();

			Fragment fragment = context.inflateFragment(lockedFragment.getId());
			fragment.setLock(null);

			int count = fragmentRepository.update(fragment);
			if (count != 1) {
				log.info("UpdateMarquee.handleRequest: unlock after failed update count: {}", count);
			}

			tx.commit();

			Marquee marquee = context.inflateMarquee(marqueeRepository.findByFragment(fragment).get());

			MqttAsyncClient client = context.getPublisherClient();
			log.info("UpdateMarquee.handleRequest: publishing unlocked fragment after failed marquee update");

			FragmentPublishDTO dto = new FragmentPublishDTO(fragment, marquee);
			dto.publish(client);

		} catch (Exception unlockError) {
			log.error("UpdateMarquee.handleRequest: failed to unlock fragment after failed marquee update", unlockError);

			if (tx.isActive()) {
				tx.rollback();
			}
		}
	}
}
