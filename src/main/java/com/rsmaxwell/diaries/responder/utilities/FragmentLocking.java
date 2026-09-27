package com.rsmaxwell.diaries.responder.utilities;


import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.slf4j.Logger;

import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public final class FragmentLocking {

	private FragmentLocking() {
	}

	/** Null type remains compatible with legacy MARQUEE candidates. */
	public static void requireMarqueeCompatible(Fragment fragment) throws RpcStatusException {
		if ((fragment.getType() != null && fragment.getType() != FragmentType.MARQUEE)
				|| fragment.getImageId() != null)
			throw RpcStatusException.badRequest("Marquee requires a MARQUEE-compatible Fragment without an Image reference");
	}

	public static void requireLockedByCaller(Fragment fragment, Claims claims) throws Exception {
		Long userId = claims.get("userId", Long.class);
		String sessionId = claims.get("sessionId", String.class);

		LockInfo lock = fragment.getLock();

		if (lock == null || !lock.isLocked()) {
			throw RpcStatusException.badRequest("Fragment is not locked");
		}

		if (!lock.isLockedBy(userId, sessionId)) {
			throw RpcStatusException.conflict("Fragment is locked by another session");
		}
	}

	public static void requireUnlockAllowed(Fragment fragment, Claims claims) throws Exception {
		Long userId = claims.get("userId", Long.class);
		String sessionId = claims.get("sessionId", String.class);

		LockInfo lock = fragment.getLock();

		if (lock == null || !lock.isLocked()) {
			return;
		}

		if (!lock.isLockedBy(userId, sessionId)) {
			throw RpcStatusException.conflict("Fragment is locked by another user.");
		}
	}

	public static ResolvedFragmentState clearLockInCurrentTransaction(DiaryContext context, Fragment fragment) throws Exception {

		FragmentRepository fragmentRepository = context.getFragmentRepository();

		fragment.setLock(null);

		int count = fragmentRepository.update(fragment);
		if (count != 1) {
			throw new IllegalStateException("Expected to update 1 fragment, updated " + count + " for fragment id=" + fragment.getId());
		}

		return context.resolveFragmentState(fragment);
	}

	public static void publish(DiaryContext context, ResolvedFragmentState resolvedState) throws Exception {

		MqttAsyncClient client = context.getPublisherClient();

		FragmentPublishDTO dto = new FragmentPublishDTO(resolvedState.getFragment(), resolvedState.getMarquee());

		dto.publish(client);
	}

	public static void unlockAfterFailedEdit(DiaryContext context, Long fragmentId, Logger log, String reason) {

		if (fragmentId == null) {
			return;
		}

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		ResolvedFragmentState resolvedState = null;

		try {
			tx.begin();

			Fragment fragment = context.inflateFragment(fragmentId);
			resolvedState = clearLockInCurrentTransaction(context, fragment);

			tx.commit();

		} catch (Exception unlockError) {
			if (tx.isActive()) {
				tx.rollback();
			}

			log.error("{}: failed to unlock fragment id={}", reason, fragmentId, unlockError);
			return;
		}

		try {
			publish(context, resolvedState);
		} catch (Exception publishError) {
			log.error("{}: failed to publish unlocked fragment id={}", reason, fragmentId, publishError);
		}
	}
}
