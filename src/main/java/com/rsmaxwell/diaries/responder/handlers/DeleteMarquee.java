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

		tx.begin();

		try {
			Long id = Utilities.getLong(args, "id");
			Marquee ownerHint = context.inflateMarquee(id);
			Long fragmentId = ownerHint.getFragment().getId();
			if (em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
					.setParameter("id", fragmentId).getResultList().isEmpty())
				throw RpcStatusException.badRequest("Fragment not found");
			marquee = context.inflateMarquee(id);
			fragment = marquee.getFragment();
			FragmentLocking.requireMarqueeCompatible(fragment);
			if (fragment.getType() == com.rsmaxwell.diaries.responder.model.FragmentType.IMAGE) {
				com.rsmaxwell.diaries.responder.utilities.ImageFragmentExtensionsWritePolicy.requireEnabled(context);
				if (!args.containsKey("version") || !marquee.getVersion().equals(Utilities.getLong(args, "version")))
					throw RpcStatusException.conflict("IMAGE Marquee version changed before deletion");
			} else if (args.containsKey("version") && !marquee.getVersion().equals(Utilities.getLong(args, "version")))
				throw RpcStatusException.conflict("Marquee version changed before deletion");
			if (fragment.getPageId() == null || marquee.getPage() == null
					|| !fragment.getPageId().equals(marquee.getPage().getId()))
				throw RpcStatusException.badRequest("Marquee and Fragment Page mismatch");

			FragmentLocking.requireLockedByCaller(fragment, claims);
			int marqueeCount = marqueeRepository.delete(marquee);
			if (marqueeCount != 1) throw RpcStatusException.conflict("Marquee changed during deletion");

			FragmentLocking.clearLockInCurrentTransaction(context, fragment);

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("DeleteMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);

			if (tx.isActive()) {
				tx.rollback();
			}


			throw e;

		} catch (Exception e) {
			log.error("DeleteMarquee.handleRequest: unexpected error; rolling back transaction", e);

			if (tx.isActive()) {
				tx.rollback();
			}


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
