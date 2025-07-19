package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
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

		Fragment fragment;
		try {
			Long id = Utilities.getLong(args, "id");
			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			Long marqueeId = Utilities.getLong(args, "marqueeId");
			String text = Utilities.getString(args, "text");

			Marquee marquee = context.inflateMarquee(marqueeId);

			fragment = context.inflateFragment(id);
			fragment.setYear(year);
			fragment.setMonth(month);
			fragment.setDay(day);
			fragment.setMarquee(marquee);
			fragment.setText(text);

		} catch (Exception e) {
			log.info("UpdateFragment.handleRequest: Exception: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the fragment in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			int count = fragmentRepository.update(fragment);
			if (count != 1) {
				log.info("UpdateFragment.handleRequest: number of records updated: {}", count);
			}
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now publish the Fragment to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		fragment.publish(client);

		return Response.success(fragment.getId());
	}
}
