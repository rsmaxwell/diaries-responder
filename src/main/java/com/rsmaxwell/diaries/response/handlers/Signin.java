package com.rsmaxwell.diaries.response.handlers;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mindrot.jbcrypt.BCrypt;

import com.rsmaxwell.diaries.common.response.SigninReply;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;

public class Signin extends RequestHandler {

	private static final Logger log = LogManager.getLogger(Signin.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		log.debug("Signin.handleRequest");

		String username = Utilities.getString(args, "username");
		String password = Utilities.getString(args, "password");

		DiaryContext context = (DiaryContext) ctx;
		PersonRepository personRepository = context.getPersonRepository();
		String secret = context.getSecret();

		Optional<PersonDTO> optional = personRepository.findByUsername(username);

		if (optional.isEmpty()) {
			return Response.badRequest("bad username or password");
		}

		PersonDTO person = optional.get();

		boolean ok = BCrypt.checkpw(password, person.getPasswordHash());
		if (!ok) {
			return Response.badRequest("bad username or password");
		}

		String accessToken = Authorization.getToken(secret, "access", context.getRefreshPeriod(), ChronoUnit.SECONDS);
		String refreshToken = Authorization.getToken(secret, "refresh", context.getRefreshExpiration(), ChronoUnit.SECONDS);
		Integer refreshPeriod = context.getRefreshPeriod();
		Long id = person.getId();

		SigninReply payload = new SigninReply(accessToken, refreshToken, refreshPeriod, id);
		return Response.success(payload);
	}
}
