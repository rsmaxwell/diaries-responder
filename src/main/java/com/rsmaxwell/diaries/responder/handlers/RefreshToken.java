package com.rsmaxwell.diaries.responder.handlers;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.common.response.RefreshTokenReply;
import com.rsmaxwell.diaries.responder.dto.PersonDTO;
import com.rsmaxwell.diaries.responder.model.UserStatus;
import com.rsmaxwell.diaries.responder.repository.PersonRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;

public class RefreshToken extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(RefreshToken.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("RefreshToken.handleRequest");

		String refreshToken = Authorization.getRefreshToken(args);
		DiaryContext context = (DiaryContext) ctx;

		Claims claims = Authorization.checkToken(context, "refresh", refreshToken);
		log.info("RefreshToken.handleRequest: Authorization.check: OK!");

		String sessionId = (String) claims.get("sessionId");
		if (sessionId == null) {
			throw RpcStatusException.internalError("RefreshToken did not contain the sessionId");
		}

		Object userIdClaim = claims.get("userId");
		if (!(userIdClaim instanceof Number)) {
			throw RpcStatusException.internalError("RefreshToken did not contain a numeric userId");
		}
		Long id = ((Number) userIdClaim).longValue();

		PersonRepository personRepository = context.getPersonRepository();
		Optional<PersonDTO> optional = personRepository.findById(id);
		if (optional.isEmpty()) {
			throw RpcStatusException.internalError("userId '" + id + "' not found");
		}

		PersonDTO person = optional.get();

		if (person.getStatus() == null) {
			throw RpcStatusException.forbidden("account status not set");
		}

		if (person.getStatus() != UserStatus.ACTIVE) {
			throw RpcStatusException.forbidden("account not active");
		}

		if (person.getRole() == null) {
			throw RpcStatusException.forbidden("account has no role assigned");
		}

		String username = person.getUsername();
		String knownAs = person.getKnownas();
		String status = person.getStatus().name();
		String role = person.getRole().name();

		Map<String, Object> accessTokenClaims = new HashMap<String, Object>();
		accessTokenClaims.put("userId", id);
		accessTokenClaims.put("username", username);
		accessTokenClaims.put("knownAs", knownAs);
		accessTokenClaims.put("sessionId", sessionId);
		accessTokenClaims.put("status", status);
		accessTokenClaims.put("role", role);

		String secret = context.getSecret();
		int expiration = context.getRefreshPeriod();

		String token = Authorization.getTokenWithClaims(secret, "access", expiration, ChronoUnit.SECONDS, accessTokenClaims);
		Integer refreshPeriod = context.getRefreshPeriod();
		RefreshTokenReply reply = new RefreshTokenReply(token, refreshPeriod);

		Response response = Response.success();
		response.setPayload(reply);
		return response;
	}
}
