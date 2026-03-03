package com.rsmaxwell.diaries.responder.handlers;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mindrot.jbcrypt.BCrypt;

import com.rsmaxwell.diaries.common.response.SigninReply;
import com.rsmaxwell.diaries.responder.dto.PersonDTO;
import com.rsmaxwell.diaries.responder.repository.PersonRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

public class Signin extends RequestHandler {

	private static final Logger log = LogManager.getLogger(Signin.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		log.debug("Signin.handleRequest");

		String incomingUsername = Utilities.getString(args, "username");
		String incomingPassword = Utilities.getString(args, "password");
		String sessionId = Utilities.getString(args, "sessionId");

		DiaryContext context = (DiaryContext) ctx;
		PersonRepository personRepository = context.getPersonRepository();
		String secret = context.getSecret();

		Optional<PersonDTO> optional = personRepository.findByUsername(incomingUsername);

		if (optional.isEmpty()) {
			return Response.badRequest("bad username or password");
		}

		PersonDTO person = optional.get();

		boolean ok = BCrypt.checkpw(incomingPassword, person.getPasswordHash());
		if (!ok) {
			return Response.badRequest("bad username or password");
		}

		Long id = person.getId();
		String username = person.getUsername();
		String knownAs = person.getKnownas();

		Map<String, Object> claims = new HashMap<String, Object>();
		claims.put("userId", id);
		claims.put("username", username);
		claims.put("knownAs", knownAs);
		claims.put("sessionId", sessionId);

		String accessToken = Authorization.getTokenWithClaims(secret, "access", context.getRefreshPeriod(), ChronoUnit.SECONDS, claims);
		String refreshToken = Authorization.getToken(secret, "refresh", context.getRefreshExpiration(), ChronoUnit.SECONDS);
		Integer refreshPeriod = context.getRefreshPeriod();

		SigninReply payload = new SigninReply(accessToken, refreshToken, refreshPeriod, id, username, knownAs, sessionId);
		return Response.success(payload);
	}
}
