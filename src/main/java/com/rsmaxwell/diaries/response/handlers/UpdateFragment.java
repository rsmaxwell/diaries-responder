package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
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
import com.rsmaxwell.diaries.response.model.Marquee;
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

public class UpdateFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
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

			// (1) get the original Fragment
			originalFragment = context.inflateFragment(id);

			// (2) get the incoming Fragment

			//@formatter:off
			FragmentDBDTO fragmentDBDTO = FragmentDBDTO.builder()
					.id(id)
					.version(version)
					.year(year)
					.month(month)
					.day(day)
					.sequence(sequence)
					.text(text)
					.build();
			//@formatter:on		

			incomingFragment = new Fragment(fragmentDBDTO);

			// (3) check and bump the version
			incomingFragment.checkAndIncrementVersion(originalFragment);

			// (4) save to database
			int count = fragmentRepository.update(incomingFragment);
			if (count != 1) {
				log.info("UpdateFragment.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (BadRequest e) {
			tx.rollback();
			return Response.badRequest(e.getMessage());
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// (5) Remove the original Fragment from the TopicTree

		Marquee marquee = null;
		Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(incomingFragment);
		if (optionalMarqueeDTO.isPresent()) {
			MarqueeDBDTO marqueeDTO = optionalMarqueeDTO.get();
			marquee = context.inflateMarquee(marqueeDTO);
		}

		MqttAsyncClient client = context.getPublisherClient();
		if (originalFragment.keyFieldsChanged(incomingFragment)) {
			log.info("UpdateFragment.handleRequest: removing the original fragment from the TopicTree");
			FragmentPublishDTO dto = new FragmentPublishDTO(originalFragment, marquee);
			dto.remove(client);
		}

		// (6) publish the Fragment to the topic tree
		log.info("UpdateFragment.handleRequest: publishing the incoming fragment to the TopicTree");
		FragmentPublishDTO dto = new FragmentPublishDTO(incomingFragment, marquee);
		dto.publish(client);

		return Response.success(incomingFragment.getId());
	}
}
