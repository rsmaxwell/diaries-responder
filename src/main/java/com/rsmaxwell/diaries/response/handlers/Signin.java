package com.rsmaxwell.diaries.response.handlers;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mindrot.jbcrypt.BCrypt;

import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Result;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;

public class Signin extends RequestHandler {

	private static final Logger log = LogManager.getLogger(Signin.class);

	@Override
	public Result handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		String username = Utilities.getString(args, "username");
		String password = Utilities.getString(args, "password");

		DiaryContext context = (DiaryContext) ctx;
		PersonRepository personRepository = context.getPersonRepository();
		String secret = context.getSecret();

		Optional<Person> optional = personRepository.findFullByUsername(username);

		if (optional.isEmpty()) {
			return Result.badRequest("bad username or password");
		}

		Person person = optional.get();

		boolean ok = BCrypt.checkpw(password, person.getPasswordHash());
		if (ok) {
			Response response = Response.success();
			response.put("accessToken", Authorization.getToken(secret, "access", context.getRefreshPeriod(), ChronoUnit.SECONDS));
			response.put("refreshToken", Authorization.getToken(secret, "refresh", context.getRefreshExpiration(), ChronoUnit.SECONDS));
			response.put("refreshPeriod", context.getRefreshPeriod());
			response.put("id", person.getId());
			return new Result(response, false);
		} else {
			return Result.badRequest("bad username or password");
		}
	}
}
