package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mindrot.jbcrypt.BCrypt;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.diaries.response.utilities.Field;
import com.rsmaxwell.mqtt.rpc.common.Result;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class Register extends RequestHandler {

	private static final Logger log = LogManager.getLogger(Register.class);
	public static final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
	public static final String defaultRegion = "GB";

	@Override
	public Result handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		DiaryContext context = (DiaryContext) ctx;
		PersonRepository personRepository = context.getPersonRepository();

		Person person = validate(personRepository, args);

		EntityManager entityManager = context.getEntityManager();
		EntityTransaction tx = null;
		try {
			tx = entityManager.getTransaction();
			tx.begin();

			entityManager.persist(person);

			tx.commit();

			log.info(String.format("Person Registered: %s", person));

			return Result.success(person.getId());

		} catch (Exception e) {
			log.catching(e);
			if (tx != null) {
				tx.rollback();
			}
			return Result.badRequest(e.getMessage());
		}
	}

	private Person validate(PersonRepository personRepository, Map<String, Object> args) throws BadRequest {

		String username = new Field("username", args).min(3).max(20).toString();
		String password = new Field("password", args).min(3).max(20).toString();
		String firstname = new Field("firstname", args).min(3).max(20).toString();
		String lastname = new Field("lastname", args).min(3).max(20).toString();
		String knownas = new Field("knownas", args).min(3).max(20).toString();
		String email = new Field("email", args).email().toString();
		String phone = new Field("phone", args).phone().toString();

		Optional<PersonDTO> optional = personRepository.findByUsername(username);
		if (optional.isPresent()) {
			throw new BadRequest("Already Registered");
		}

		String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

		PhoneNumber phoneNumber = null;
		try {
			phoneNumber = phoneNumberUtil.parse(phone, defaultRegion);
		} catch (Exception e) {
			throw new BadRequest("Not a valid phone number");
		}
		int countryCode = phoneNumber.getCountryCode();
		long nationalNumber = phoneNumber.getNationalNumber();

		return new Person(username, passwordHash, firstname, lastname, knownas, email, countryCode, nationalNumber);
	}
}
