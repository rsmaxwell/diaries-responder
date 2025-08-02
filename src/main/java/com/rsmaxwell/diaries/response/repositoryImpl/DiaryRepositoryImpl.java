package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class DiaryRepositoryImpl extends AbstractCrudRepository<Diary, DiaryDTO, Long> implements DiaryRepository {

	public DiaryRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "diary";
	}

	@Override
	public <S extends Diary> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends Diary> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("name");
		list.add("sequence");
		list.add("version");
		return list;
	}

	@Override
	public <S extends Diary> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getName());
		list.add(entity.getSequence());
		list.add(entity.getVersion());
		return list;
	}

	@Override
	public DiaryDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String name = getStringFromSqlResult(result, 1, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 2, null);
		Long version = getLongFromSqlResult(result, 3, null);

		//@formatter:off
		return DiaryDTO.builder()
				.id(id)
				.name(name)
				.sequence(sequence)
				.version(version)
				.build();
		//@formatter:on		
	}

	@Override
	public Optional<DiaryDTO> findById(Long id) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("id", id)
				.build();
		// @formatter:on

		List<DiaryDTO> list = new ArrayList<DiaryDTO>();
		for (DiaryDTO dto : find(where)) {
			list.add(dto);
		}

		return singleItem(list);
	}

	@Override
	public Optional<DiaryDTO> findByName(String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("name", name)
				.build();
		// @formatter:on

		List<DiaryDTO> list = new ArrayList<DiaryDTO>();
		for (DiaryDTO dto : find(where)) {
			list.add(dto);
		}

		return singleItem(list);
	}

	@Override
	protected String orderBy() {
		return "ORDER BY sequence NULLS LAST, id";
	}
}