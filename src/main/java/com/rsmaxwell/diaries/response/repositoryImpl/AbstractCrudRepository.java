package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
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

	protected EntityManager entityManager;

	public AbstractCrudRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	abstract public String getTable();

	abstract public String getKeyField();

	abstract public <S extends T> String getKeyValue(S entity);

	abstract public <S extends DTO> String getDTOKeyValue(S dto);

	abstract public <S extends T> void setKeyValue(S entity, Object value);

	abstract public <S extends DTO> void setDTOKeyValue(S dto, Object value);

	abstract public List<String> getFields();

	abstract public List<String> getDTOFields();

	abstract public <S extends T> List<Object> getValues(S entity);

	abstract public <S extends DTO> List<Object> getDTOValues(S dto);

	abstract public DTO newDTO(Object[] result);

	public EntityManager getEntityManager() {
		return entityManager;
	}

	@Override
	public long count() {
		String sql = String.format("select count(*) from %s", getTable());
		Query query = entityManager.createNativeQuery(sql);
		Object object = query.getSingleResult();
		return ((Number) object).longValue();
	}

	@Override
	public int delete(T entity) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getKeyField(), getKeyValue(entity));
		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteAll --> count: %d", count));
		return count;
	}

	@Override
	public int deleteAll() {
		String sql = String.format("delete from %s", getTable());
		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteAll --> count: %d", count));
		return count;
	}

	@Override
	public int deleteAll(Iterable<? extends T> entities) {
		int totalCount = 0;
		for (T entity : entities) {
			int count = delete(entity);
			totalCount += count;
		}
		return totalCount;
	}

	@Override
	public int deleteAllById(Iterable<? extends ID> ids) {
		int totalCount = 0;
		for (ID id : ids) {
			int count = deleteById(id);
			totalCount += count;
		}
		return totalCount;
	}

	@Override
	public int deleteById(ID id) {
		String sql = String.format("delete from %s where %s = %s", getTable(), getKeyField(), quote(id));
		log.info(String.format("sql: %s", sql));

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("deleteById --> count: %d", count));
		return count;
	}

	@Override
	public boolean existsById(ID id) {
		String sql = String.format("select exists(select 1 from %s where %s = %s)", getTable(), getKeyField(), quote(id));
		log.debug(String.format("sql: %s", sql));

		Query query = entityManager.createNativeQuery(sql);
		return (Boolean) query.getSingleResult();
	}

	protected String orderBy() {
		return "";
	}

	@Override
	@SuppressWarnings("unchecked")
	public Iterable<DTO> findAll() {

		StringBuffer sql = new StringBuffer();
		sql.append("select ");
		sql.append(getKeyField());
		sql.append(",  ");

		String seperator = "";
		for (String field : getDTOFields()) {
			sql.append(seperator);
			sql.append(field);
			seperator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());

		String orderClause = orderBy().trim();
		if (!orderClause.isEmpty()) {
			sql.append(" ");
			sql.append(orderClause);
		}

		Query query = entityManager.createNativeQuery(sql.toString());
		List<Object[]> resultList = query.getResultList();

		List<DTO> list = new ArrayList<DTO>();
		for (Object[] result : resultList) {
			DTO x = newDTO(result);
			list.add(x);
		}

		return list;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Iterable<DTO> findById(Iterable<ID> ids) {

		List<DTO> list = new ArrayList<DTO>();
		for (ID id : ids) {

			StringBuffer sql = new StringBuffer();
			sql.append("select ");
			sql.append(getKeyField());
			sql.append(", ");

			String seperator = "";
			for (String field : getDTOFields()) {
				sql.append(seperator);
				sql.append(field);
				seperator = ", ";
			}

			sql.append(" from ");
			sql.append(getTable());
			sql.append(" where ");
			sql.append(getKeyField());
			sql.append(" = ");
			sql.append(quote(id));

			String orderClause = orderBy().trim();
			if (!orderClause.isEmpty()) {
				sql.append(" ");
				sql.append(orderClause);
			}

			Query query = entityManager.createNativeQuery(sql.toString());
			List<Object[]> resultList = query.getResultList();

			for (Object[] result : resultList) {
				DTO x = newDTO(result);
				list.add(x);
			}
		}

		return list;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Optional<DTO> findById(ID id) {

		List<DTO> list = new ArrayList<DTO>();

		StringBuffer sql = new StringBuffer();
		sql.append("select ");
		sql.append(getKeyField());
		sql.append(", ");

		String seperator = "";
		for (String field : getDTOFields()) {
			sql.append(seperator);
			sql.append(field);
			seperator = ", ";
		}

		sql.append(" from ");
		sql.append(getTable());
		sql.append(" where ");
		sql.append(getKeyField());
		sql.append(" = ");
		sql.append(quote(id));

		Query query = entityManager.createNativeQuery(sql.toString());
		List<Object[]> resultList = query.getResultList();

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

		String sql = String.format("insert into %s ( %s ) values ( %s ) returning %s", getTable(), assignments, valuesBuffer, getKeyField());
		Query query = entityManager.createNativeQuery(sql);
		Object value = query.getSingleResult();
		log.info(String.format("save --> %s: %s", getKeyField(), value.toString()));

		setKeyValue(entity, value);

		return entity;
	}

	@Override
	public <S extends DTO> S saveDTO(S entity) throws Exception {

		String separator = "";
		StringBuffer assignments = new StringBuffer();
		for (String field : getDTOFields()) {
			assignments.append(separator);
			assignments.append(field);
			separator = ", ";
		}

		separator = "";
		StringBuffer valuesBuffer = new StringBuffer();
		for (Object value : getDTOValues(entity)) {
			valuesBuffer.append(separator);
			valuesBuffer.append(quote(value));
			separator = ", ";
		}

		String sql = String.format("insert into %s ( %s ) values ( %s ) returning %s", getTable(), assignments, valuesBuffer, getKeyField());
		Query query = entityManager.createNativeQuery(sql);
		Object value = query.getSingleResult();
		log.info(String.format("save --> %s: %s", getKeyField(), value.toString()));

		setDTOKeyValue(entity, value);

		return entity;
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

		String sql = String.format("update %s set %s where %s = %s", getTable(), assignments, getKeyField(), getKeyValue(entity));

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("update --> count: %d", count));

		return count;
	}

	@Override
	public <S extends DTO> int updateDTO(S dto) throws Exception {
		String separator = "";
		StringBuilder assignments = new StringBuilder();
		List<String> fields = getDTOFields();
		List<Object> values = getDTOValues(dto);

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

		String sql = String.format("update %s set %s where %s = %s", getTable(), assignments, getKeyField(), getDTOKeyValue(dto));

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("update --> count: %d", count));

		return count;
	}

	@Override
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

	public String quote(Object value) {

		if (value instanceof Number) {
			return value.toString();
		}

		StringBuffer sb = new StringBuffer();
		sb.append("'");
		sb.append(value);
		sb.append("'");
		return sb.toString();
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
}
