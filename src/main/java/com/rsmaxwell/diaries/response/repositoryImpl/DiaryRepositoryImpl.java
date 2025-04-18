package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class DiaryRepositoryImpl extends AbstractCrudRepository<Diary, Diary, Long> implements DiaryRepository {

	public DiaryRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	public String getTable() {
		return "diary";
	}

	public <S extends Diary> String getPrimaryKeyValueAsString(S entity) {
		return entity.getId().toString();
	}

	public String convertPrimaryKeyValueToString(Long id) {
		return id.toString();
	}

	public <S extends Diary> void setPrimaryKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	public String getPrimaryKeyField() {
		return "id";
	}

	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("name");
		return list;
	}

	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("id");
		list.add("name");
		return list;
	}

	public <S extends Diary> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getName());
		return list;
	}

	public Diary newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String name = getStringFromSqlResult(result, 1, null);
		return new Diary(id, name);
	}

	public Optional<Diary> findByName(String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("name", name)
				.build();
		// @formatter:on

		List<Diary> list = new ArrayList<Diary>();
		for (Diary x : find(where)) {
			list.add(x);
		}

		return singleItem(list);
	}
}