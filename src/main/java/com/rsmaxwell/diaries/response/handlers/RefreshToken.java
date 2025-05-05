package com.rsmaxwell.diaries.response.handlers;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;

public class RefreshToken extends RequestHandler {

	private static final Logger log = LogManager.getLogger(RefreshToken.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("RefreshToken.handleRequest");

		String refreshToken = Authorization.getRefreshToken(args);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "refresh", refreshToken) == null) {
			log.info("GetDiaries.handleRequest: Authorization.check: Failed!");
			return Response.unauthorized();
		}
		log.info("RefreshToken.handleRequest: Authorization.check: OK!");

		String secret = context.getSecret();
		int expiration = context.getRefreshExpiration();

		Response response = Response.success();
		response.setPayload(Authorization.getToken(secret, "access", expiration, ChronoUnit.SECONDS));
		return response;
	}
}
