package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.response.repository.CrudRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

public abstract class AbstractCrudRepository<T, DTO, ID> implements CrudRepository<T, DTO, ID> {

	private static final Logger log = LogManager.getLogger(AbstractCrudRepository.class);

	private EntityManager entityManager;

	public AbstractCrudRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	abstract public String getTable();

	abstract public String getPrimaryKeyField();

	abstract public <S extends T> Object getPrimaryKeyValueAsString(S entity);

	abstract public String convertPrimaryKeyValueToString(ID id);

	abstract public <S extends T> void setPrimaryKeyValue(S entity, Object value);

	abstract public List<String> getFields();

	abstract public List<String> getDTOFields();

	abstract public <S extends T> List<Object> getValues(S entity);

	abstract public DTO newDTO(Object[] result);

	public EntityManager getEntityManager() {
		return entityManager;
	}

	public long count() {
		String sql = String.format("select count(*) from %s", getTable());
		Query query = entityManager.createNativeQuery(sql);
		Object object = query.getSingleResult();
		return ((Number) object).longValue();
	}

	public void delete(T entity) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getPrimaryKeyField(), getPrimaryKeyValueAsString(entity));
		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteAll --> count: %d", count));
	}

	public void deleteAll() {
		String sql = String.format("delete from %s", getTable());
		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteAll --> count: %d", count));
	}

	public void deleteAll(Iterable<? extends T> entities) {
		for (T entity : entities) {
			delete(entity);
		}
	}

	public void deleteAllById(Iterable<? extends ID> ids) {
		for (ID id : ids) {
			deleteById(id);
		}
	}

	public void deleteById(ID id) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getPrimaryKeyField(), convertPrimaryKeyValueToString(id));
		log.info(String.format("sql: %s", sql));

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteById --> count: %d", count));
	}

	public boolean existsById(ID id) {
		String sql = String.format("select exists(select 1 from %s where %s = %s)", getTable(), getPrimaryKeyField(), convertPrimaryKeyValueToString(id));
		log.debug(String.format("sql: %s", sql));

		Query query = entityManager.createNativeQuery(sql);
		return (Boolean) query.getSingleResult();
	}

	@SuppressWarnings("unchecked")
	public Iterable<DTO> findAll() {

		StringBuffer sql = new StringBuffer();
		sql.append("select ");

		String seperator = "";
		for (String field : getDTOFields()) {
			sql.append(seperator);
			sql.append(field);
			seperator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());

		Query query = entityManager.createNativeQuery(sql.toString());
		List<Object[]> resultList = query.getResultList();

		List<DTO> list = new ArrayList<DTO>();
		for (Object[] result : resultList) {
			DTO x = newDTO(result);
			list.add(x);
		}

		return list;
	}

	@SuppressWarnings("unchecked")
	public Iterable<DTO> findById(Iterable<ID> ids) {

		List<DTO> list = new ArrayList<DTO>();
		for (ID id : ids) {

			StringBuffer sql = new StringBuffer();
			sql.append("select ");

			String seperator = "";
			for (String field : getDTOFields()) {
				sql.append(seperator);
				sql.append(field);
				seperator = ", ";
			}

			sql.append(" from ");
			sql.append(getTable());
			sql.append(" where ");
			sql.append(getPrimaryKeyField());
			sql.append(" = ");
			sql.append(convertPrimaryKeyValueToString(id));

			Query query = entityManager.createNativeQuery(sql.toString());
			List<Object[]> resultList = query.getResultList();

			for (Object[] result : resultList) {
				DTO x = newDTO(result);
				list.add(x);
			}
		}

		return list;
	}

	@SuppressWarnings("unchecked")
	public Optional<DTO> findById(ID id) {

		List<DTO> list = new ArrayList<DTO>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");

		String seperator = "";
		for (String field : getDTOFields()) {
			sql.append(seperator);
			sql.append(field);
			seperator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());
		sql.append(" where ");
		sql.append(getPrimaryKeyField());
		sql.append(" = ");
		sql.append(convertPrimaryKeyValueToString(id));

		Query query = entityManager.createNativeQuery(sql.toString());
		List<Object[]> resultList = query.getResultList();

		for (Object[] result : resultList) {
			DTO x = newDTO(result);
			list.add(x);
		}

		return singleItem(list);
	}

	public Iterable<DTO> find(String where) {

		List<DTO> list = new ArrayList<DTO>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");

		String seperator = "";
		for (String field : getDTOFields()) {
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
			DTO x = newDTO(result);
			list.add(x);
		}

		return list;
	}

	public <S extends T> S save(S entity) throws Exception {

		String separator = "";
		StringBuffer assignments = new StringBuffer();
		for (String field : getFields()) {
			assignments.append(separator);
			assignments.append(field);
			separator = ", ";
		}

		separator = "";
		StringBuffer valuesBuffer = new StringBuffer();
		for (Object value : getValues(entity)) {
			valuesBuffer.append(separator);
			valuesBuffer.append(quote(value));
			separator = ", ";
		}

		String sql = String.format("insert into %s ( %s ) values ( %s ) returning %s", getTable(), assignments, valuesBuffer, getPrimaryKeyField());
		Query query = entityManager.createNativeQuery(sql);
		Object value = query.getSingleResult();
		log.info(String.format("save --> %s: %s", getPrimaryKeyField(), value.toString()));

		setPrimaryKeyValue(entity, value);

		return entity;
	}

	public <S extends T> S update(S entity) throws Exception {
		String separator = "";
		StringBuilder assignments = new StringBuilder();
		List<String> fields = getFields();
		List<Object> values = getValues(entity);

		for (int i = 0; i < fields.size(); i++) {
			String field = fields.get(i);
			Object value = values.get(i);
			if (field.equals(getPrimaryKeyField())) {
				continue; // don't update the primary key
			}
			assignments.append(separator);
			assignments.append(field).append(" = ").append(quote(value));
			separator = ", ";
		}

		String sql = String.format("update %s set %s where %s = %s", getTable(), assignments, getPrimaryKeyField(), getPrimaryKeyValueAsString(entity));

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("update --> count: %d", count));

		return entity;
	}

	public <S extends T> Iterable<S> saveAll(Iterable<S> entities) throws Exception {
		List<S> list = new ArrayList<S>();
		for (S entity : entities) {
			list.add(save(entity));
		}
		return list;
	}

	@SuppressWarnings("unchecked")
	public List<Object[]> getResultList(String sql) {
		Query query = entityManager.createNativeQuery(sql);
		return query.getResultList();
	}

	public Optional<DTO> singleItem(List<DTO> list) {

		if (list.size() <= 0) {
			return Optional.empty();
		}

		DTO item = list.get(0);
		return Optional.of(item);
	}

	public String quote(Object value) throws Exception {
		if (value instanceof String) {
			StringBuffer sb = new StringBuffer();
			sb.append("'");
			sb.append(value);
			sb.append("'");
			return sb.toString();
		} else if (value instanceof Number) {
			StringBuffer sb = new StringBuffer();
			sb.append(value);
			return sb.toString();
		}
		throw new Exception(String.format("Unexpected type: %s", value.getClass().getSimpleName()));
	}

	protected String getStringFromSqlResult(Object[] result, int index, String defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}

		return (String) result[index];
	}

	protected Integer getIntegerFromSqlResult(Object[] result, int index, Integer defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Number value = (Number) result[index];
		if (value == null) {
			return defaultValue;
		}
		return value.intValue();
	}

	protected Long getLongFromSqlResult(Object[] result, int index, Long defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Number value = (Number) result[index];
		if (value == null) {
			return defaultValue;
		}
		return value.longValue();
	}
}
