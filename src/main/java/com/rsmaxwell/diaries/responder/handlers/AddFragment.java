package com.rsmaxwell.diaries.responder.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentAndMarquee;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;

public class AddFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(AddFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	private static final double MIN_MARQUEE_SIZE = 40.0;

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("AddFragment.handleRequest: args: {}", mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("AddFragment.handleRequest: Authorization.check: OK!");

		Page page;
		Fragment fragment;
		Marquee marquee;

		try {
			Long pageId = Utilities.getLong(args, "pageId");

			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence").setScale(4);
			String text = Utilities.getString(args, "text");

			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			width = Math.max(width, MIN_MARQUEE_SIZE);
			height = Math.max(height, MIN_MARQUEE_SIZE);

			page = context.inflatePage(pageId);

			Long id = 0L;
			Long version = 0L;

			//@formatter:off
            FragmentDBDTO fragmentDTO = FragmentDBDTO.builder()
                    .id(id)
                    .year(year)
                    .month(month)
                    .day(day)
                    .sequence(sequence)
                    .text(text)
                    .version(version)
                    .build();
			//@formatter:on

			fragment = new Fragment(fragmentDTO);

			//@formatter:off			
			marquee = Marquee.builder()
					.id(id)
					.page(page)
					.fragment(fragment)
					.x(x)
					.y(y)
					.width(width)
					.height(height)
					.version(version)
					.build();
			//@formatter:on

		} catch (Exception e) {
			log.info("AddFragment.handleRequest: bad args: {}", mapper.writeValueAsString(args));
			throw RpcStatusException.badRequest(e.getMessage());
		}

		// First add the new Fragment to the database
		Fragment savedFragment;
		Marquee savedMarquee;

		try {
			FragmentAndMarquee result = context.save(fragment, marquee);
			savedFragment = result.getFragment();
			savedMarquee = result.getMarquee();
		} catch (Exception e) {
			throw RpcStatusException.internalError(e.getMessage());
		}

		// Now publish the Fragment (and its marquee) to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(savedFragment, savedMarquee);
		fragmentPublishDTO.publish(client);

		MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(savedMarquee);
		marqueePublishDTO.publish(client, page.getDiary().getId());

		return Response.success(fragmentPublishDTO);
	}
}
