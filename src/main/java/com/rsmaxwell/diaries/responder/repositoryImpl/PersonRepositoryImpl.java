package com.rsmaxwell.diaries.responder.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.rsmaxwell.diaries.responder.dto.PersonDTO;
import com.rsmaxwell.diaries.responder.model.Person;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.model.UserStatus;
import com.rsmaxwell.diaries.responder.repository.PersonRepository;
import com.rsmaxwell.diaries.responder.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class PersonRepositoryImpl extends AbstractCrudRepository<Person, PersonDTO, Long> implements PersonRepository {

	public static final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
	public static final String defaultRegion = "GB";

	public PersonRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "person";
	}

	@Override
	public <S extends Person> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends Person> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("username");
		list.add("passwordHash");
		list.add("firstName");
		list.add("lastName");
		list.add("knownas");
		list.add("email");
		list.add("countryCode");
		list.add("nationalNumber");
		list.add("status");
		list.add("role");
		return list;
	}

	@Override
	public <S extends Person> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getUsername());
		list.add(entity.getPasswordHash());
		list.add(entity.getFirstName());
		list.add(entity.getLastName());
		list.add(entity.getKnownas());
		list.add(entity.getEmail());
		list.add(entity.getCountryCode());
		list.add(entity.getNationalNumber());
		list.add(entity.getStatus() == null ? null : entity.getStatus().name());
		list.add(entity.getRole() == null ? null : entity.getRole().name());
		return list;
	}

	@Override
	public PersonDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String username = getStringFromSqlResult(result, 1, null);
		String passwordHash = getStringFromSqlResult(result, 2, null);
		String firstName = getStringFromSqlResult(result, 3, null);
		String lastName = getStringFromSqlResult(result, 4, null);
		String knownas = getStringFromSqlResult(result, 5, null);
		String email = getStringFromSqlResult(result, 6, null);
		Integer countryCode = getIntegerFromSqlResult(result, 7, 0);
		Long nationalNumber = getLongFromSqlResult(result, 8, 0L);
		String statusValue = getStringFromSqlResult(result, 9, null);
		String roleValue = getStringFromSqlResult(result, 10, null);

		UserStatus status = toUserStatus(statusValue);
		Role role = toRole(roleValue);

		//@formatter:off
		return PersonDTO.builder()
				.id(id)
				.username(username)
				.passwordHash(passwordHash)
				.firstName(firstName)
				.lastName(lastName)
				.knownas(knownas)
				.email(email)
				.countryCode(countryCode)
				.nationalNumber(nationalNumber)
				.status(status)
				.role(role)
				.build();
		//@formatter:on
	}

	private UserStatus toUserStatus(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return UserStatus.valueOf(value);
	}

	private Role toRole(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return Role.valueOf(value);
	}

	public String phoneNumberFromDTO(Integer countryCode, Long nationalNumber) {
		if ((countryCode == null) || (nationalNumber == null)) {
			return null;
		}
		PhoneNumber number = new PhoneNumber();
		number.setCountryCode(countryCode);
		number.setNationalNumber(nationalNumber);
		return phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);
	}

	@Override
	public Optional<PersonDTO> findByUsername(String username) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("username", username)
				.build();
		// @formatter:on

		Iterable<PersonDTO> people = find(where.toString());

		List<PersonDTO> list = new ArrayList<PersonDTO>();
		for (PersonDTO dto : people) {
			list.add(dto);
		}

		return singleItem(list);
	}

	public Optional<PersonDTO> singleFullItem(List<PersonDTO> list) {

		if (list.size() <= 0) {
			return Optional.empty();
		}

		PersonDTO item = list.get(0);
		return Optional.of(item);
	}
}
