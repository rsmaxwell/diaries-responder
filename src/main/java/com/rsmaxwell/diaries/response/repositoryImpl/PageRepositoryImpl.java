package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class PageRepositoryImpl extends AbstractCrudRepository<Page, PageDTO, Long> implements PageRepository {

	public PageRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	public String getTable() {
		return "page";
	}

	public <S extends Page> String getPrimaryKeyValueAsString(S entity) {
		return entity.getId().toString();
	}

	public String convertPrimaryKeyValueToString(Long id) {
		return id.toString();
	}

	public <S extends Page> void setPrimaryKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	public String getPrimaryKeyField() {
		return "id";
	}

	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("diary_id");
		list.add("name");
		list.add("extension");
		list.add("width");
		list.add("height");
		return list;
	}

	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("id");
		list.add("name");
		list.add("extension");
		list.add("width");
		list.add("height");
		return list;
	}

	public <S extends Page> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getDiary().getId());
		list.add(entity.getName());
		list.add(entity.getExtension());
		list.add(entity.getWidth());
		list.add(entity.getHeight());
		return list;
	}

	public PageDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		String name = getStringFromSqlResult(result, 1, null);
		String extension = getStringFromSqlResult(result, 2, null);
		Integer width = getIntegerFromSqlResult(result, 3, null);
		Integer height = getIntegerFromSqlResult(result, 4, null);
		return new PageDTO(id, name, extension, width, height);
	}

	public Iterable<PageDTO> findAllByDiary(Diary diary) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", diary.getId())
				.build();
		// @formatter:on

		return find(where);
	}

	public Iterable<PageDTO> findAllByDiaryId(Long diaryId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", diaryId)
				.build();
		// @formatter:on

		return find(where);
	}

	public Optional<PageDTO> findByDiaryAndName(Diary diary, String name) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", diary.getId())
				.add("name", name)
				.build();
		// @formatter:on

		List<PageDTO> list = new ArrayList<PageDTO>();
		for (PageDTO x : find(where)) {
			list.add(x);
		}

		return singleItem(list);
	}
}