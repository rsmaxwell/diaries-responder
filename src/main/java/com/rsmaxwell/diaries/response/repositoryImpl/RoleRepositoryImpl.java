package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.RoleDTO;
import com.rsmaxwell.diaries.response.model.Role;
import com.rsmaxwell.diaries.response.repository.RoleRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class RoleRepositoryImpl extends AbstractCrudRepository<Role, RoleDTO, Long> implements RoleRepository {

	public RoleRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "role";
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public <S extends Role> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends RoleDTO> String getDTOKeyValue(S dto) {
		return dto.getId().toString();
	}

	@Override
	public <S extends Role> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public <S extends RoleDTO> void setDTOKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("name");
		return list;
	}

	@Override
	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("name");
		return list;
	}

	@Override
	public <S extends Role> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getName());
		return list;
	}

	@Override
	public <S extends RoleDTO> List<Object> getDTOValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getName());
		return list;
	}

	@Override
	public RoleDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String name = getStringFromSqlResult(result, 1, null);
		return new RoleDTO(id, name);
	}

	@Override
	public Optional<RoleDTO> findByName(String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("name", name)
				.build();
		// @formatter:on

		List<RoleDTO> list = new ArrayList<RoleDTO>();
		for (RoleDTO x : find(where)) {
			list.add(x);
		}

		return singleItem(list);
	}
}