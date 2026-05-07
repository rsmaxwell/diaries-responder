package com.rsmaxwell.diaries.responder.handlers;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.common.response.SigninReply;
import com.rsmaxwell.diaries.responder.dto.PersonDTO;
import com.rsmaxwell.diaries.responder.model.UserStatus;
import com.rsmaxwell.diaries.responder.repository.PersonRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

public class Signin extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(Signin.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.trace("Entering method");

		log.debug("Signin.handleRequest");

		String incomingUsername = Utilities.getString(args, "username");
		String incomingPassword = Utilities.getString(args, "password");
		String sessionId = Utilities.getString(args, "sessionId");

		DiaryContext context = (DiaryContext) ctx;
		PersonRepository personRepository = context.getPersonRepository();
		String secret = context.getSecret();

		Optional<PersonDTO> optional = personRepository.findByUsername(incomingUsername);

		if (optional.isEmpty()) {
			throw RpcStatusException.badRequest("bad username or password");
		}

		PersonDTO person = optional.get();

		boolean ok = BCrypt.checkpw(incomingPassword, person.getPasswordHash());
		if (!ok) {
			throw RpcStatusException.badRequest("bad username or password");
		}

		if (person.getStatus() == null) {
			throw RpcStatusException.forbidden("account status not set");
		}

		if (person.getStatus() == UserStatus.PENDING) {
			throw RpcStatusException.forbidden("account pending approval");
		}

		if (person.getStatus() == UserStatus.DISABLED) {
			throw RpcStatusException.forbidden("account disabled");
		}

		if (person.getStatus() != UserStatus.ACTIVE) {
			throw RpcStatusException.forbidden("account not active");
		}

		if (person.getRole() == null) {
			throw RpcStatusException.forbidden("account has no role assigned");
		}

		Long id = person.getId();
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
		String accessToken = Authorization.getTokenWithClaims(secret, "access", context.getRefreshPeriod(), ChronoUnit.SECONDS, accessTokenClaims);

		Map<String, Object> refreshTokenClaims = new HashMap<String, Object>();
		refreshTokenClaims.put("userId", id);
		refreshTokenClaims.put("sessionId", sessionId);
		String refreshToken = Authorization.getTokenWithClaims(secret, "refresh", context.getRefreshExpiration(), ChronoUnit.SECONDS, refreshTokenClaims);

		Integer refreshPeriod = context.getRefreshPeriod();
		SigninReply payload = new SigninReply(accessToken, refreshToken, refreshPeriod, id, username, knownAs, sessionId, status, role);
		return Response.success(payload);
	}
}
