package com.rsmaxwell.diaries.responder.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hibernate.Session;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.responder.repository.CrudRepository;

import jakarta.persistence.EntityManager;

public abstract class AbstractCrudRepository<T, DTO, ID> implements CrudRepository<T, DTO, ID> {

	private static final Logger log = LoggerFactory.getLogger(AbstractCrudRepository.class);

	protected EntityManager entityManager;

	public AbstractCrudRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	abstract public String getTable();

	abstract public String getKeyField();

	abstract public <S extends T> String getKeyValue(S entity);

	abstract public <S extends T> void setKeyValue(S entity, Object value);

	abstract public List<String> getFields();

	abstract public <S extends T> List<Object> getValues(S entity);

	@SuppressWarnings("unchecked")
	public ID idFromResult(Object result) {
		return (ID) result;
	}

	abstract public DTO newDTO(Object[] result);

	public EntityManager getEntityManager() {
		return entityManager;
	}

	@Override
	public long count() {
		String sql = String.format("select count(*) from %s", getTable());
		Long result = getNativeSingleResult(sql, Long.class);
		return result;
	}

	@Override
	public int deleteById(ID id) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getKeyField(), quote(id));
		int count = executeMutation(sql);
		log.info(String.format("delete --> count: %d", count));
		return count;
	}

	@Override
	public int delete(T entity) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getKeyField(), quote(getKeyValue(entity)));
		int count = executeMutation(sql);
		log.info(String.format("delete --> count: %d", count));
		return count;
	}

	@Override
	public int deleteAll() {
		String sql = String.format("delete from %s", getTable());
		int count = executeMutation(sql);
		log.info(String.format("deleteAll --> count: %d", count));
		return count;
	}

	@Override
	public boolean existsById(ID id) {
		String sql = String.format("select exists(select 1 from %s where %s = %s)", getTable(), getKeyField(), quote(id));
		log.debug(String.format("sql: %s", sql));

		Boolean result = getNativeSingleResult(sql, Boolean.class);
		return Boolean.TRUE.equals(result);
	}

	protected String orderBy() {
		return "";
	}

	@SuppressWarnings("unchecked")
	@Override
	public ID getId(Object value) throws Exception {
		return (ID) value;
	}

	@Override
	public Iterable<DTO> findAll() {

		StringBuffer sql = new StringBuffer();
		sql.append("select ");
		sql.append(getKeyField());
		sql.append(",  ");

		String separator = "";
		for (String field : getFields()) {
			sql.append(separator);
			sql.append(field);
			separator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());

		String orderClause = orderBy().trim();
		if (!orderClause.isEmpty()) {
			sql.append(" ");
			sql.append(orderClause);
		}

		List<Object[]> resultList = getNativeResultList(sql.toString(), Object[].class);

		List<DTO> list = new ArrayList<DTO>();
		for (Object[] result : resultList) {
			DTO x = newDTO(result);
			list.add(x);
		}

		return list;
	}

	@Override
	public Optional<DTO> findById(ID id) {

		List<DTO> list = new ArrayList<DTO>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");
		sql.append(getKeyField());
		sql.append(", ");

		String separator = "";
		for (String field : getFields()) {
			sql.append(separator);
			sql.append(field);
			separator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());
		sql.append(" where ");
		sql.append(getKeyField());
		sql.append(" = ");
		sql.append(quote(id));

		List<Object[]> resultList = getNativeResultList(sql.toString(), Object[].class);

		for (Object[] result : resultList) {
			DTO dto = newDTO(result);
			list.add(dto);
		}

		return singleItem(list);
	}

	@Override
	public Iterable<DTO> find(String where) {

		List<DTO> list = new ArrayList<DTO>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");
		sql.append(getKeyField());
		sql.append(", ");

		String separator = "";
		for (String field : getFields()) {
			sql.append(separator);
			sql.append(field);
			separator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());
		sql.append(" where ");
		sql.append(where);

		String orderClause = orderBy().trim();
		if (!orderClause.isEmpty()) {
			sql.append(" ");
			sql.append(orderClause);
		}

		List<Object[]> results = getResultList(sql.toString());

		for (Object[] result : results) {
			DTO dto = newDTO(result);
			list.add(dto);
		}

		return list;
	}

	@Override
	public <S extends T> ID save(S entity) throws Exception {

		String separator = "";
		StringBuilder assignments = new StringBuilder();
		for (String field : getFields()) {
			assignments.append(separator).append(field);
			separator = ", ";
		}

		separator = "";
		StringBuilder valuesBuffer = new StringBuilder();
		for (Object value : getValues(entity)) {
			valuesBuffer.append(separator).append(quote(value));
			separator = ", ";
		}

		// @formatter:off
		String sql = String.format(
				"insert into %s ( %s ) values ( %s ) returning %s",
				getTable(), assignments, valuesBuffer, getKeyField()
		);
		// @formatter:on

		List<Object> rows = getNativeResultList(sql, Object.class);
		Object one = rows.isEmpty() ? null : rows.get(0);
		Object keyValue = one;

		if (keyValue == null) {
			throw new IllegalStateException("Insert did not return a value for " + getKeyField());
		}

		log.info(String.format("save %s --> %s: %s", entity.getClass().getSimpleName(), getKeyField(), keyValue));

		setKeyValue(entity, keyValue);

		return idFromResult(keyValue);
	}

	@Override
	public <S extends T> int update(S entity) throws Exception {
		String separator = "";
		StringBuilder assignments = new StringBuilder();
		List<String> fields = getFields();
		List<Object> values = getValues(entity);

		for (int i = 0; i < fields.size(); i++) {
			String field = fields.get(i);
			Object value = values.get(i);
			if (field.equals(getKeyField())) {
				continue; // don't update the primary key
			}
			assignments.append(separator);
			assignments.append(field).append(" = ").append(quote(value));
			separator = ", ";
		}

		String sql = String.format("update %s set %s where %s = %s", getTable(), assignments, getKeyField(), quote(getKeyValue(entity)));

		int count = executeMutation(sql);
		log.info(String.format("update --> count: %d", count));

		return count;
	}

	public List<Object[]> getResultList(String sql) {
		return getNativeResultList(sql, Object[].class);
	}

	public Optional<DTO> singleItem(List<DTO> list) {

		if (list.size() <= 0) {
			return Optional.empty();
		}

		DTO item = list.get(0);
		return Optional.of(item);
	}

	public String quote(Object value) {

		if (value == null) {
			return "null"; // SQL NULL (no quotes)
		}

		if (value instanceof Number) {
			return value.toString();
		}

		if (value instanceof Boolean) {
			return ((Boolean) value) ? "true" : "false";
		}

		// Escape single quotes for SQL
		String s = value.toString().replace("'", "''");
		return "'" + s + "'";
	}

	protected String getStringFromSqlResult(Object[] result, int index, String defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Object obj = result[index];
		if (obj == null) {
			return defaultValue;
		}
		if (obj instanceof String) {
			return (String) obj;
		}
		return defaultValue;
	}

	protected Integer getIntegerFromSqlResult(Object[] result, int index, Integer defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Object obj = result[index];
		if (obj == null) {
			return defaultValue;
		}
		if (obj instanceof Number) {
			return ((Number) obj).intValue();
		}
		return defaultValue;
	}

	protected Long getLongFromSqlResult(Object[] result, int index, Long defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Object obj = result[index];
		if (obj == null) {
			return defaultValue;
		}
		if (obj instanceof Number) {
			return ((Number) obj).longValue();
		}
		return defaultValue;
	}

	protected Double getDoubleFromSqlResult(Object[] result, int index, Double defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Object obj = result[index];
		if (obj == null) {
			return defaultValue;
		}
		if (obj instanceof Number) {
			return ((Number) obj).doubleValue();
		}
		return defaultValue;
	}

	protected BigDecimal getBigDecimalFromSqlResult(Object[] result, int index, BigDecimal defaultValue) {
		if (index >= result.length) {
			return defaultValue;
		}
		Object obj = result[index];
		if (obj == null) {
			return defaultValue;
		}

		if (obj instanceof Number) {
			Number number = (Number) obj;
			BigDecimal decimal;

			if (number instanceof BigDecimal) {
				decimal = (BigDecimal) number;
			} else if (number instanceof Long || number instanceof Integer) {
				decimal = BigDecimal.valueOf(number.longValue());
			} else if (number instanceof Double || number instanceof Float) {
				decimal = BigDecimal.valueOf(number.doubleValue());
			} else {
				decimal = new BigDecimal(number.toString());
			}

			return decimal;
		}

		// Optionally handle string values from raw SQL
		if (obj instanceof String) {
			try {
				return new BigDecimal((String) obj);
			} catch (NumberFormatException e) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	protected Session getSession() {
		return entityManager.unwrap(Session.class);
	}

	protected int executeMutation(String sql) {
		MutationQuery query = getSession().createNativeMutationQuery(sql);
		return query.executeUpdate();
	}

	protected <R> List<R> getNativeResultList(String sql, Class<R> resultClass) {
		NativeQuery<R> query = getSession().createNativeQuery(sql, resultClass);
		return query.getResultList();
	}

	protected <R> R getNativeSingleResult(String sql, Class<R> resultClass) {
		NativeQuery<R> query = getSession().createNativeQuery(sql, resultClass);
		return query.getSingleResult();
	}
}
