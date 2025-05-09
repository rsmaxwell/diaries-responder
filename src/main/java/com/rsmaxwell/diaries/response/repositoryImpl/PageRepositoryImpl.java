package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class PageRepositoryImpl extends AbstractCrudRepository<Page, PageDTO, Long> implements PageRepository {

	public PageRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "page";
	}

	@Override
	public <S extends Page> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends PageDTO> String getDTOKeyValue(S dto) {
		return dto.getId().toString();
	}

	@Override
	public <S extends Page> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public <S extends PageDTO> void setDTOKeyValue(S dto, Object value) {
		dto.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("diary_id");
		list.add("name");
		list.add("sequence");
		list.add("extension");
		list.add("width");
		list.add("height");
		return list;
	}

	@Override
	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("diary_id");
		list.add("name");
		list.add("sequence");
		list.add("extension");
		list.add("width");
		list.add("height");
		return list;
	}

	@Override
	public <S extends Page> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getDiary().getId());
		list.add(entity.getName());
		list.add(entity.getSequence());
		list.add(entity.getExtension());
		list.add(entity.getWidth());
		list.add(entity.getHeight());
		return list;
	}

	@Override
	public <S extends PageDTO> List<Object> getDTOValues(S dto) {
		List<Object> list = new ArrayList<Object>();
		list.add(dto.getDiaryId());
		list.add(dto.getName());
		list.add(dto.getSequence());
		list.add(dto.getExtension());
		list.add(dto.getWidth());
		list.add(dto.getHeight());
		return list;
	}

	@Override
	public PageDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long diaryId = getLongFromSqlResult(result, 1, null);
		String name = getStringFromSqlResult(result, 2, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 3, null);
		String extension = getStringFromSqlResult(result, 4, null);
		Integer width = getIntegerFromSqlResult(result, 5, null);
		Integer height = getIntegerFromSqlResult(result, 6, null);
		return new PageDTO(id, diaryId, name, sequence, extension, width, height);
	}

	@Override
	public Iterable<PageDTO> findAllByDiary(Long diaryId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", diaryId)
				.build();
		// @formatter:on

		return find(where);
	}

	@Override
	public Optional<PageDTO> findByDiaryAndName(Long diaryId, String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", diaryId)
				.add("name", name)
				.build();
		// @formatter:on

		List<PageDTO> list = new ArrayList<PageDTO>();
		for (PageDTO x : find(where)) {
			list.add(x);
		}

		return singleItem(list);
	}

	@Override
	protected String orderBy() {
		return "ORDER BY sequence NULLS LAST, id";
	}
}