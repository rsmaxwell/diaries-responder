package com.rsmaxwell.diaries.responder.utilities;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.validator.routines.EmailValidator;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;

public class Field {

	private static EmailValidator emailValidator = EmailValidator.getInstance();
	private static PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
	public static final String defaultRegion = "GB";

	private final String name;
	private final String value;

	public Field(String name, Map<String, Object> args) throws BadRequest {
		this.name = name;

		try {
			this.value = Utilities.getString(args, name);
		} catch (Exception e) {
			throw new BadRequest(e.getMessage());
		}
	}

	public Field min(int limit) throws BadRequest {
		if (value.length() < limit) {
			throw new BadRequest(String.format("'%s' too short", name));
		}
		return this;
	}

	public Field max(int limit) throws BadRequest {
		if (value.length() > limit) {
			throw new BadRequest(String.format("'%s' too long", name));
		}
		return this;
	}

	public Field pattern(String regex) throws BadRequest {
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(value);

		if (!matcher.matches()) {
			throw new BadRequest(String.format("'%s' bad formatted incorrectly", name));
		}
		return this;
	}

	public Field email() throws BadRequest {
		boolean valid = emailValidator.isValid(value);
		if (!valid) {
			throw new BadRequest(String.format("'%s' bad format", name));
		}
		return this;
	}

	public Field phone() throws BadRequest {
		PhoneNumber number;

		try {
			number = phoneNumberUtil.parse(value, defaultRegion);
		} catch (Exception e) {
			throw new BadRequest(String.format("'%s' bad format: %s", name, e.getMessage()));
		}

		if (!phoneNumberUtil.isValidNumber(number)) {
			throw new BadRequest(String.format("'%s' bad format", name));
		}

		return this;
	}

	@Override
	public String toString() {
		return value;
	}
}
