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
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentLocking;
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

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Marquee marquee = null;
		Fragment fragment = null;
		Fragment lockedFragment = null;

		tx.begin();

		try {
			Long id = Utilities.getLong(args, "id");
			marquee = context.inflateMarquee(id);
			fragment = marquee.getFragment();

			FragmentLocking.requireLockedByCaller(fragment, claims);
			lockedFragment = fragment;

			int marqueeCount = marqueeRepository.delete(marquee);
			if (marqueeCount != 1) {
				log.info("DeleteMarquee.handleRequest: number of marquee records deleted: {}", marqueeCount);
			}

			FragmentLocking.clearLockInCurrentTransaction(context, fragment);

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("DeleteMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);

			if (tx.isActive()) {
				tx.rollback();
			}

			FragmentLocking.unlockAfterFailedEdit(context, lockedFragment == null ? null : lockedFragment.getId(), log, "DeleteMarquee.handleRequest");

			throw e;

		} catch (Exception e) {
			log.error("DeleteMarquee.handleRequest: unexpected error; rolling back transaction", e);

			if (tx.isActive()) {
				tx.rollback();
			}

			FragmentLocking.unlockAfterFailedEdit(context, lockedFragment == null ? null : lockedFragment.getId(), log, "DeleteMarquee.handleRequest");

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
}
