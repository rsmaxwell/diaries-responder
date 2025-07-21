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
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
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

		log.info("UpdateFragment.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdateFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Fragment incomingFragment;
		Fragment originalFragment;
		try {
			Long id = Utilities.getLong(args, "id");
			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			Long marqueeId = Utilities.getLong(args, "marqueeId");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			Long version = Utilities.getLong(args, "version");
			String text = Utilities.getString(args, "text");

			Marquee marquee = context.inflateMarquee(marqueeId);
			FragmentDBDTO fragmentDTO = new FragmentDBDTO(id, marquee, year, month, day, sequence, version, text);
			incomingFragment = new Fragment(fragmentDTO);

		} catch (Exception e) {
			log.info("UpdateFragment.handleRequest: Exception: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the fragment in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			// (1) get the original Fragment
			Optional<FragmentDBDTO> optionalOriginalFragmentDTO = fragmentRepository.findById(incomingFragment.getId());
			if (optionalOriginalFragmentDTO.isEmpty()) {
				throw new BadRequest(String.format("original Fragment id %d not found", incomingFragment.getId()));
			}
			FragmentDBDTO fragmentDTO = optionalOriginalFragmentDTO.get();
			originalFragment = new Fragment(fragmentDTO);

			// (2) check the incoming version number
			if (incomingFragment.getVersion() != originalFragment.getVersion()) {
				throw new BadRequest(String.format("Stale update. incoming version: %d, original version: %d", incomingFragment.getVersion(), originalFragment.getVersion()));
			}

			// (3) bump the version
			Long version = incomingFragment.getVersion();
			incomingFragment.setVersion(version + 1);

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

		// (5) Remove the original Fragment from the TopicTree & add the incoming Fragment
		MqttAsyncClient client = context.getPublisherClient();
		if (originalFragment.keyFieldsChanged(incomingFragment)) {
			log.info("UpdateFragment.handleRequest: removing the original fragment from the TopicTree");
			originalFragment.remove(client);
		}

		log.info("UpdateFragment.handleRequest: publishing the incomming fragment to the TopicTree");
		incomingFragment.publish(client);

		return Response.success(incomingFragment.getId());
	}
}
