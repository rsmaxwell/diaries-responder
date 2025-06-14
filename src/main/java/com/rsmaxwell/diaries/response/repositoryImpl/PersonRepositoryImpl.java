package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

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

		// String phone = phoneNumberFromDTO(countryCode, nationalNumber);
		return new PersonDTO(id, username, passwordHash, firstName, lastName, knownas, email, countryCode, nationalNumber);
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

	public PersonDTO newPersonDTO(Object[] result) {
		Long id = ((Number) result[0]).longValue();
		String username = (String) result[1];
		String passwordHash = (String) result[2];
		String firstName = (String) result[3];
		String lastName = (String) result[4];
		String knownas = (String) result[5];
		String email = (String) result[6];
		int countryCode = ((Number) result[7]).intValue();
		long nationalNumber = ((Number) result[8]).longValue();

		return new PersonDTO(id, username, passwordHash, firstName, lastName, knownas, email, countryCode, nationalNumber);
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
