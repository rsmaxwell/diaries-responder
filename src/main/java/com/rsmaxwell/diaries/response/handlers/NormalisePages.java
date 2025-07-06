package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class NormalisePages extends RequestHandler {

	private static final Logger log = LogManager.getLogger(NormalisePages.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("NormalisePages.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("NormalisePages.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("NormalisePages.handleRequest: Authorization.check: OK!");

		MqttAsyncClient client = context.getPublisherClient();
		PageRepository pageRepository = context.getPageRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		List<Page> updates = new ArrayList<>();

		BigDecimal sequence = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");

		tx.begin();
		try {
			for (PageDTO pageDTO : pageRepository.findAll()) {
				BigDecimal currentSeq = pageDTO.getSequence();

				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("diary id:%d, name:%s already has correct sequence number", dto.getId(), dto.getName()));
				} else {
					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("Updating page id: %d, name: %s, sequence: %s --> %s", pageDTO.getId(), pageDTO.getName(), currentSeqStr, sequence.toPlainString()));

					Page page = context.toPage(pageDTO);

					page.setSequence(sequence);
					pageRepository.update(page);
					updates.add(page);
				}

				sequence = sequence.add(increment);
			}
			tx.commit();

		} catch (Exception ex) {
			tx.rollback();
			throw ex;
		}

		// Publish the updates
		for (Page page : updates) {
			log.info(String.format("Publishing page id:%d, name:%s --> sequence: %s", page.getId(), page.getName(), page.getSequence().toPlainString()));
			page.publishAll(client);
		}

		return Response.success(updates.size());
	}
}
