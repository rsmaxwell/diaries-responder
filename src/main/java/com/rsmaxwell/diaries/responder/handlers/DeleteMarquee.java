package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
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

public class DeleteMarquee extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DeleteMarquee.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteMarquee.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("DeleteMarquee.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Marquee marquee = null;
		Fragment fragment = null;

		boolean callerOwnsFragmentLock = false;
		boolean fragmentUnlockedInTransaction = false;

		tx.begin();

		try {
			Long id = Utilities.getLong(args, "id");
			marquee = context.inflateMarquee(id);
			fragment = marquee.getFragment();

			Long lockUserId = claims.get("userId", Long.class);
			String lockSessionId = claims.get("sessionId", String.class);

			LockInfo lock = fragment.getLock();

			if (lock == null || !lock.isLocked()) {
				throw RpcStatusException.badRequest("Fragment is not locked");
			}

			if (!lock.isLockedBy(lockUserId, lockSessionId)) {
				throw RpcStatusException.conflict("Fragment is locked by another session");
			}

			callerOwnsFragmentLock = true;

			int marqueeCount = marqueeRepository.delete(marquee);
			if (marqueeCount != 1) {
				log.info("DeleteMarquee.handleRequest: number of marquee records deleted: {}", marqueeCount);
			}

			fragment.setLock(null);

			int fragmentCount = fragmentRepository.update(fragment);
			if (fragmentCount != 1) {
				log.info("DeleteMarquee.handleRequest: number of fragment records updated: {}", fragmentCount);
			}

			fragmentUnlockedInTransaction = true;

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("DeleteMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);

			if (tx.isActive()) {
				tx.rollback();
			}

			unlockFragmentAfterFailedMarqueeDelete(context, fragmentRepository, marqueeRepository, fragment, callerOwnsFragmentLock, fragmentUnlockedInTransaction);

			throw e;

		} catch (Exception e) {
			log.error("DeleteMarquee.handleRequest: unexpected error; rolling back transaction", e);

			if (tx.isActive()) {
				tx.rollback();
			}

			unlockFragmentAfterFailedMarqueeDelete(context, fragmentRepository, marqueeRepository, fragment, callerOwnsFragmentLock, fragmentUnlockedInTransaction);

			throw e;
		}

		// remove the marquee from the topic tree
		MqttAsyncClient client = context.getPublisherClient();

		Page page = marquee.getPage();
		Diary diary = page.getDiary();

		log.info("DeleteMarquee.handleRequest: publishing unlocked fragment after marquee delete");

		// Marquee has been deleted, so publish the fragment with no marquee.
		FragmentPublishDTO fragmentDto = new FragmentPublishDTO(fragment, null);
		fragmentDto.publish(client);

		MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(marquee);
		marqueePublishDTO.remove(client, diary.getId());

		return Response.success(marquee.getId());
	}

	private void unlockFragmentAfterFailedMarqueeDelete(DiaryContext context, FragmentRepository fragmentRepository, MarqueeRepository marqueeRepository, Fragment lockedFragment,
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
				log.info("DeleteMarquee.handleRequest: unlock after failed delete count: {}", count);
			}

			tx.commit();

			Marquee marquee = null;
			var optionalMarqueeDTO = marqueeRepository.findByFragment(fragment);
			if (optionalMarqueeDTO.isPresent()) {
				marquee = context.inflateMarquee(optionalMarqueeDTO.get());
			}

			MqttAsyncClient client = context.getPublisherClient();

			log.info("DeleteMarquee.handleRequest: publishing unlocked fragment after failed marquee delete");

			FragmentPublishDTO dto = new FragmentPublishDTO(fragment, marquee);
			dto.publish(client);

		} catch (Exception unlockError) {
			log.error("DeleteMarquee.handleRequest: failed to unlock fragment after failed marquee delete", unlockError);

			if (tx.isActive()) {
				tx.rollback();
			}
		}
	}
}
