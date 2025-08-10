package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class NormaliseFragments extends RequestHandler {

	private static final Logger log = LogManager.getLogger(NormaliseFragments.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("NormaliseFragments.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("NormaliseFragments.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("NormaliseFragments.handleRequest: Authorization.check: OK!");

		MqttAsyncClient client = context.getPublisherClient();
		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		log.info("NormaliseFragments.handleRequest: get the date arguments");

		Integer year;
		Integer month;
		Integer day;
		try {
			year = Utilities.getInteger(args, "year");
			month = Utilities.getInteger(args, "month");
			day = Utilities.getInteger(args, "day");

		} catch (Exception e) {
			log.info("NormaliseFragments.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		List<Fragment> updates = new ArrayList<>();

		BigDecimal sequence = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");

		tx.begin();
		try {
			for (FragmentDBDTO fragmentDTO : fragmentRepository.findAllByDate(year, month, day)) {
				BigDecimal currentSeq = fragmentDTO.getSequence();

				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("fragment id:%d, already has correct sequence number: %s", fragmentDTO.getId(), sequence.toPlainString()));
				} else {
					Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(fragmentDTO.getId());
					MarqueeDBDTO marqueeDTO = null;
					if (optionalMarqueeDTO.isPresent()) {
						marqueeDTO = optionalMarqueeDTO.get();
					}

					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("Updating fragment id: %d, sequence: %s -> %s", fragmentDTO.getId(), currentSeqStr, sequence.toPlainString()));

					Fragment fragment = new Fragment(fragmentDTO, marqueeDTO.getId());

					fragment.setSequence(sequence);
					fragment.incrementVersion();
					fragmentRepository.update(fragment);
					updates.add(fragment);
				}

				sequence = sequence.add(increment);
			}
			tx.commit();

		} catch (Exception ex) {
			tx.rollback();
			throw ex;
		}

		// Publish the updates
		for (Fragment fragment : updates) {
			// @formatter:off
			log.info(String.format("Publishing fragment %d:%d:%d - %d --> sequence: %s",
					fragment.getYear(), fragment.getMonth(), fragment.getDay(), fragment.getId(),
					fragment.getSequence().toPlainString()));
			// @formatter:on
			FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment);
			fragmentPublishDTO.publish(client);
		}

		return Response.success(updates.size());
	}
}
