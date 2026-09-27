package com.rsmaxwell.diaries.responder.handlers;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.rsmaxwell.diaries.responder.model.FragmentType;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.ResolvedFragmentState;
import com.rsmaxwell.diaries.responder.utilities.FragmentLocking;
import com.rsmaxwell.diaries.responder.utilities.FragmentSequenceNormaliser;
import com.rsmaxwell.diaries.responder.utilities.SequenceNumber;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(UpdateFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("UpdateFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment incomingFragment;
		Fragment originalFragment;
		List<Fragment> normalisedFragments;

		if (tx.isActive()) throw new IllegalStateException("UpdateFragment owns its transaction");
		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			BigDecimal sequence = SequenceNumber.normalise(Utilities.getBigDecimal(args, "sequence"));
			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			String text = Utilities.getString(args, "text");

			// Serialize the version/owner check with other writes to this Fragment.
			if (em.createNativeQuery("select id from fragment where id = :id for update", Long.class)
					.setParameter("id", id).getResultList().isEmpty())
				throw RpcStatusException.badRequest("Fragment not found");

			// (1) load original from DB (includes current lock state)
			originalFragment = context.inflateFragment(id);

			// (2) enforce: must own the lock
			FragmentLocking.requireLockedByCaller(originalFragment, claims);
			LockInfo originalLock = originalFragment.getLock();
			requireUnchangedIdentity(args, originalFragment);

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
		        .pageId(originalFragment.getPageId())
		        .type(originalFragment.getType())
		        .imageId(originalFragment.getImageId())
		        .lock(originalLock) // carry lock forward so we can clear it after version bump
		        .build();
			// @formatter:on			

			// (4) check and bump the version
			incomingFragment = new Fragment(originalFragment.getPage(), fragmentDBDTO);
			incomingFragment.checkAndIncrementVersion(originalFragment);
			applyImageSelection(context, args, incomingFragment);
			if (incomingFragment.getType() == FragmentType.IMAGE
					&& context.getMarqueeRepository().findByFragment(incomingFragment).isPresent())
				throw RpcStatusException.badRequest("IMAGE Fragment cannot have a Marquee");

			// (5) release the lock after successful update
			incomingFragment.setLock(null); // simplest: DB columns become NULL

			// (6) save to database
			int count = fragmentRepository.update(incomingFragment);
			if (count != 1) {
				throw RpcStatusException.conflict("Fragment changed during update");
			}

			// Normalise every affected date in the same transaction. This covers both a
			// date change and a sequence-only drag-and-drop reorder.
			normalisedFragments = FragmentSequenceNormaliser.normaliseAffectedDates(
					fragmentRepository,
					originalFragment,
					incomingFragment);

			tx.commit();

			/*
			 * Reload from the database so the object we publish has the same BigDecimal scale and any DB-normalised values as startup synchronisation.
			 */
			incomingFragment = context.inflateFragment(id);

		} catch (RpcStatusException e) {
			log.warn("UpdateFragment.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		} catch (Exception e) {
			log.error("UpdateFragment.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		}

		// (7) get the marquee associated with the fragment (can be null)
		ResolvedFragmentState resolvedState = context.resolveFragmentState(incomingFragment);

		// (8) If the fragment keys have changed, then remove the fragment from the topicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalFragment.keyFieldsChanged(incomingFragment)) {
			log.info("UpdateFragment.handleRequest: removing the original fragment from the TopicTree");
			FragmentPublishDTO dto = new FragmentPublishDTO(originalFragment, resolvedState.getMarquee());
			dto.remove(client);
		}

		// (9) publish the final incoming fragment and every fragment renumbered on
		// either date. De-duplicate by id because the incoming fragment may itself
		// have been renumbered.
		Map<Long, Fragment> fragmentsToPublish = new LinkedHashMap<>();
		for (Fragment fragment : normalisedFragments) {
			fragmentsToPublish.put(fragment.getId(), fragment);
		}
		fragmentsToPublish.put(incomingFragment.getId(), incomingFragment);
		log.info("UpdateFragment.handleRequest: publishing {} affected fragment(s) to the TopicTree", fragmentsToPublish.size());
		FragmentSequenceNormaliser.publish(context, fragmentsToPublish.values());

		return Response.success(incomingFragment.getId());
	}
	static void requireUnchangedIdentity(Map<String, Object> args, Fragment original) throws RpcStatusException {
		if (args.containsKey("pageId")) {
			Long pageId = args.get("pageId") == null ? null : positiveImageOrPageId(args.get("pageId"));
			if (!Objects.equals(pageId, original.getPageId()))
				throw RpcStatusException.badRequest("Fragment Page cannot be changed");
		}
		if (args.containsKey("type") && !Objects.equals(args.get("type"),
				original.getType() == null ? null : original.getType().name()))
			throw RpcStatusException.badRequest("Fragment type cannot be changed");
	}

	static void applyImageSelection(DiaryContext context, Map<String, Object> args, Fragment fragment) throws Exception {
		if (fragment.getType() != FragmentType.IMAGE) {
			if (args.get("imageId") != null || fragment.getImageId() != null)
				throw RpcStatusException.badRequest("Only IMAGE Fragments can reference an Image");
			return;
		}
		if (!args.containsKey("imageId")) return; // Preserve the persisted id, including during text-only edits.
		Long imageId = args.get("imageId") == null ? null : positiveImageOrPageId(args.get("imageId"));
        if (!com.rsmaxwell.diaries.responder.utilities.ImageFragmentWritePolicy.enabled(context)) {
            if (!Objects.equals(imageId, fragment.getImageId()))
                com.rsmaxwell.diaries.responder.utilities.ImageFragmentWritePolicy.requireEnabled(context);
            return; // An unchanged explicit reference is not an authoring mutation.
        }
		try {
			fragment.setImage(context.lockImageForFragmentWrite(imageId));
		} catch (IllegalArgumentException missing) {
			throw RpcStatusException.badRequest("Image reference could not be resolved");
		}
	}

	private static Long positiveImageOrPageId(Object value) throws RpcStatusException {
		try {
			if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException();
			long id = new BigDecimal(value.toString()).longValueExact();
			if (id <= 0) throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw RpcStatusException.badRequest("Image/Page id must be a positive integer");
		}
	}

}
