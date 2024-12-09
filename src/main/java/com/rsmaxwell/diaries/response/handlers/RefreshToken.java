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
import com.rsmaxwell.mqtt.rpc.common.Result;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;

public class RefreshToken extends RequestHandler {

	private static final Logger log = LogManager.getLogger(RefreshToken.class);

	@Override
	public Result handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("RefreshToken.handleRequest");

		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.check(context, "refresh", userProperties) == null) {
			log.info("GetDiaries.handleRequest: Authorization.check: Failed!");
			return Result.unauthorised();
		}
		log.info("RefreshToken.handleRequest: Authorization.check: OK!");

		String secret = context.getSecret();
		int expiration = context.getRefreshExpiration();
		Response response = Response.success();
		response.put("accessToken", Authorization.getToken(secret, "access", expiration, ChronoUnit.SECONDS));
		return new Result(response, false);
	}
}
