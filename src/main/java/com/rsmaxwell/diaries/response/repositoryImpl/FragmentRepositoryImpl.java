package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class FragmentRepositoryImpl extends AbstractCrudRepository<Fragment, FragmentDTO, Long> implements FragmentRepository {

	public FragmentRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	public String getTable() {
		return "fragment";
	}

	public <S extends Fragment> String getPrimaryKeyValueAsString(S entity) {
		return entity.getId().toString();
	}

	public String convertPrimaryKeyValueToString(Long id) {
		return id.toString();
	}

	public <S extends Fragment> void setPrimaryKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	public String getPrimaryKeyField() {
		return "id";
	}

	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("page_id");
		list.add("x");
		list.add("y");
		list.add("cx");
		list.add("cy");
		list.add("sequence");
		list.add("text");
		return list;
	}

	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("id");
		list.add("x");
		list.add("y");
		list.add("cx");
		list.add("cy");
		list.add("sequence");
		list.add("text");
		return list;
	}

	public <S extends Fragment> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getPage().getId());
		list.add(entity.getX());
		list.add(entity.getY());
		list.add(entity.getWidth());
		list.add(entity.getHeight());
		list.add(entity.getSequence());
		list.add(entity.getText());
		return list;
	}

	public FragmentDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Integer x = getIntegerFromSqlResult(result, 1, null);
		Integer y = getIntegerFromSqlResult(result, 2, null);
		Integer width = getIntegerFromSqlResult(result, 3, null);
		Integer height = getIntegerFromSqlResult(result, 4, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 5, null);
		String text = getStringFromSqlResult(result, 6, null);
		return new FragmentDTO(id, x, y, width, height, sequence, text);
	}

	public Iterable<FragmentDTO> findAllByPage(Page page) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("page_id", page.getId())
				.build();
		// @formatter:on

		return find(where);
	}

	public Iterable<FragmentDTO> findAllByPageId(Long pageId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("diary_id", pageId)
				.build();
		// @formatter:on

		return find(where);
	}
}