package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.repository.PersonRepository;

import jakarta.persistence.EntityManager;

public class PersonRepositoryImpl extends AbstractCrudRepository<Person, PersonDTO, Long> implements PersonRepository {

	public static final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
	public static final String defaultRegion = "GB";

	public PersonRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	public String getTable() {
		return "person";
	}

	public <S extends Person> Object getPrimaryKeyValueAsString(S entity) {
		return entity.getId();
	}

	public String convertPrimaryKeyValueToString(Long id) {
		return id.toString();
	}

	public <S extends Person> void setPrimaryKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	public String getPrimaryKeyField() {
		return "id";
	}

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

	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("id");
		list.add("username");
		list.add("firstName");
		list.add("lastName");
		list.add("knownas");
		list.add("email");
		list.add("countryCode");
		list.add("nationalNumber");
		return list;
	}

	public List<String> getAllFields() {
		List<String> list = new ArrayList<String>();
		list.add("id");
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

	public PersonDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String username = getStringFromSqlResult(result, 1, null);
		String firstName = getStringFromSqlResult(result, 2, null);
		String lastName = getStringFromSqlResult(result, 3, null);
		String knownas = getStringFromSqlResult(result, 4, null);
		String email = getStringFromSqlResult(result, 5, null);
		Integer countryCode = getIntegerFromSqlResult(result, 6, 0);
		Long nationalNumber = getLongFromSqlResult(result, 7, 0L);
		String phone = phoneNumberFromDTO(countryCode, nationalNumber);
		return new PersonDTO(id, username, firstName, lastName, knownas, email, phone);
	}

	private String phoneNumberFromDTO(Integer countryCode, Long nationalNumber) {
		if ((countryCode == null) || (nationalNumber == null)) {
			return null;
		}
		PhoneNumber number = new PhoneNumber();
		number.setCountryCode(countryCode);
		number.setNationalNumber(nationalNumber);
		return phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);
	}

	public Person newPerson(Object[] result) {
		Long id = ((Number) result[0]).longValue();
		String username = (String) result[1];
		String passwordHash = (String) result[2];
		String firstName = (String) result[3];
		String lastName = (String) result[4];
		String knownas = (String) result[5];
		String email = (String) result[6];

		int countryCode = ((Number) result[7]).intValue();
		long nationalNumber = ((Number) result[8]).longValue();

		return new Person(id, username, passwordHash, firstName, lastName, knownas, email, countryCode, nationalNumber);
	}

	@Override
	public Optional<PersonDTO> findByUsername(String username) {

		StringBuffer where = new StringBuffer();
		where.append("username");
		where.append(" = ");
		where.append("'" + username + "'");

		Iterable<PersonDTO> people = find(where.toString());

		List<PersonDTO> list = new ArrayList<PersonDTO>();
		for (PersonDTO person : people) {
			list.add(person);
		}

		return singleItem(list);
	}

	@Override
	public Optional<Person> findFullByUsername(String username) {

		StringBuffer where = new StringBuffer();
		where.append("username");
		where.append(" = ");
		where.append("'" + username + "'");

		Iterable<Person> people = findFull(where.toString());

		List<Person> list = new ArrayList<Person>();
		for (Person person : people) {
			list.add(person);
		}

		return singleFullItem(list);
	}

	public Iterable<Person> findFull(String where) {

		List<Person> list = new ArrayList<Person>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");

		String seperator = "";
		for (String field : getAllFields()) {
			sql.append(seperator);
			sql.append(field);
			seperator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());
		sql.append(" where ");
		sql.append(where);

		List<Object[]> results = getResultList(sql.toString());

		for (Object[] result : results) {
			Person x = newPerson(result);
			list.add(x);
		}

		return list;
	}

	public Optional<Person> singleFullItem(List<Person> list) {

		if (list.size() <= 0) {
			return Optional.empty();
		}

		Person item = list.get(0);
		return Optional.of(item);
	}
}
