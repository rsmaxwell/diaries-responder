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
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
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
		log.info("AddFragment.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Diary diary;
		Page page;
		Fragment fragment;
		try {
			Long id = Utilities.getLong(args, "id");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123);

			Optional<FragmentDTO> optionalFragmentDTO = fragmentRepository.findById(id);
			if (optionalFragmentDTO.isEmpty()) {
				return Response.internalError("Fragment not found: id: " + id);
			}
			FragmentDTO fragmentDTO = optionalFragmentDTO.get();

			Optional<PageDTO> optionalPageDTO = pageRepository.findById(fragmentDTO.getPageId());
			if (optionalPageDTO.isEmpty()) {
				return Response.internalError("Page not found: id: " + fragmentDTO.getPageId());
			}
			PageDTO pageDTO = optionalPageDTO.get();

			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
			if (optionalDiaryDTO.isEmpty()) {
				return Response.internalError("Diary not found: id: " + pageDTO.getDiaryId());
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();
			diary = new Diary(diaryDTO);
			page = new Page(diary, pageDTO);
			fragment = new Fragment(id, page, x, y, width, height, sequence, fragmentDTO.getText());

		} catch (Exception e) {
			log.info("UpdateFragment.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the fragment in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			fragmentRepository.update(fragment);
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now update the fragment in the topic tree
		FragmentDTO dto = fragment.toDTO();
		String topic = "diary/" + diary.getId() + "/" + page.getId() + "/" + fragment.getId();
		byte[] payload = mapper.writeValueAsBytes(dto);

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;

		log.info("UpdateFragment.handleRequest: Publishing topic: {}, fragment: {}", topic, mapper.writeValueAsString(dto));
		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(fragment.getId());
	}
}
