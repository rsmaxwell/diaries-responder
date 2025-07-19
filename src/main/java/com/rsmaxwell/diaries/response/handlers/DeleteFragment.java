package com.rsmaxwell.diaries.response.handlers;

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
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

public class DeleteFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(DeleteFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteFragment.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("DeleteFragment.handleRequest: Authorization.check: OK!");

		Fragment fragment;
		try {
			Long id = Utilities.getLong(args, "id");

			Optional<FragmentDBDTO> optionalFragmentDTO = context.findFragmentWithMarqueeById(id);
			if (optionalFragmentDTO.isEmpty()) {
				return Response.internalError("Fragment not found: id: " + id);
			}
			FragmentDBDTO fragmentDTO = optionalFragmentDTO.get();
			fragment = new Fragment(fragmentDTO);

		} catch (Exception e) {
			log.info("DeleteFragment.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First delete the fragment from the database
		context.deleteFragment(fragment);

		// Then remove the fragment from the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		fragment.remove(client);
		fragment.getMarquee().remove(client);

		return Response.success(fragment.getId());
	}
}
