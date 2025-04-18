package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.model.Role;
import com.rsmaxwell.diaries.response.repository.RoleRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class RoleRepositoryImpl extends AbstractCrudRepository<Role, Role, Long> implements RoleRepository {

	public RoleRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	public String getTable() {
		return "role";
	}

	public String getPrimaryKeyField() {
		return "id";
	}

	public <S extends Role> Object getPrimaryKeyValueAsString(S entity) {
		return entity.getId();
	}

	public String convertPrimaryKeyValueToString(Long id) {
		return id.toString();
	}

	public <S extends Role> void setPrimaryKeyValue(S entity, Object value) {
		entity.setId((Long) value);
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

	public <S extends Role> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getName());
		return list;
	}

	public Role newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String name = getStringFromSqlResult(result, 1, null);
		return new Role(id, name);
	}

	public Optional<Role> findByName(String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("name", name)
				.build();
		// @formatter:on

		List<Role> list = new ArrayList<Role>();
		for (Role x : find(where)) {
			list.add(x);
		}

		return singleItem(list);
	}
}