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

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Fragment incomingFragment;
		Fragment originalFragment;
		Marquee incomingMarquee;
		Marquee originalMarquee;

		// First update the marquee in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();


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
			if (!Double.isFinite(x) || !Double.isFinite(y) ||
					!Double.isFinite(width) || !Double.isFinite(height))
				throw RpcStatusException.badRequest("Invalid Marquee geometry");

			// Resolve owner, lock the authoritative Fragment row, then re-read it.
			Marquee ownerHint = context.inflateMarquee(id);
			Long owningFragmentId = ownerHint.getFragment().getId();
			if (em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
					.setParameter("id", owningFragmentId).getResultList().isEmpty())
				throw RpcStatusException.badRequest("Fragment not found");
			originalMarquee = context.inflateMarquee(id);

			// (2) get the fragment associated with the original marquee
			originalFragment = originalMarquee.getFragment();
			FragmentLocking.requireMarqueeCompatible(originalFragment);
			if (originalFragment.getType() == com.rsmaxwell.diaries.responder.model.FragmentType.IMAGE)
				com.rsmaxwell.diaries.responder.utilities.ImageFragmentExtensionsWritePolicy.requireEnabled(context);

			// (3) enforce: the fragment must be locked by this user/session
			FragmentLocking.requireLockedByCaller(originalFragment, claims);

			// Do not allow this request to silently move the marquee to another fragment.
			if (!originalFragment.getId().equals(fragmentId)) {
				throw RpcStatusException.badRequest("Marquee does not belong to the supplied fragment");
			}
			if (originalMarquee.getPage() == null || !pageId.equals(originalMarquee.getPage().getId())
					|| originalFragment.getPageId() == null || !originalFragment.getPageId().equals(pageId)) {
				throw RpcStatusException.badRequest("Marquee page must match the Fragment's authoritative page");
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
			if (marqueeCount != 1)
				throw RpcStatusException.conflict("Marquee changed during update");

			// (7) release the fragment lock after successful marquee update
			FragmentLocking.clearLockInCurrentTransaction(context, originalFragment);

			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("UpdateMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}


			throw e;
		} catch (Exception e) {
			log.error("UpdateMarquee.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}


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
}
