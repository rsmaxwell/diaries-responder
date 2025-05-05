package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class FragmentRepositoryImpl extends AbstractCrudRepository<Fragment, FragmentDTO, Long> implements FragmentRepository {

	public FragmentRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "fragment";
	}

	@Override
	public <S extends Fragment> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends FragmentDTO> String getDTOKeyValue(S dto) {
		return dto.getId().toString();
	}

	@Override
	public <S extends Fragment> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public <S extends FragmentDTO> void setDTOKeyValue(S dto, Object value) {
		dto.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("page_id");
		list.add("x");
		list.add("y");
		list.add("width");
		list.add("height");
		list.add("sequence");
		list.add("text");
		return list;
	}

	@Override
	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("page_id");
		list.add("x");
		list.add("y");
		list.add("width");
		list.add("height");
		list.add("sequence");
		list.add("text");
		return list;
	}

	@Override
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

	@Override
	public <S extends FragmentDTO> List<Object> getDTOValues(S dto) {
		List<Object> list = new ArrayList<Object>();
		list.add(dto.getPageId());
		list.add(dto.getX());
		list.add(dto.getY());
		list.add(dto.getWidth());
		list.add(dto.getHeight());
		list.add(dto.getSequence());
		list.add(dto.getText());
		return list;
	}

	@Override
	public FragmentDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long pageId = getLongFromSqlResult(result, 1, null);
		Double x = getDoubleFromSqlResult(result, 2, null);
		Double y = getDoubleFromSqlResult(result, 3, null);
		Double width = getDoubleFromSqlResult(result, 4, null);
		Double height = getDoubleFromSqlResult(result, 5, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 6, null);
		String text = getStringFromSqlResult(result, 7, null);
		return new FragmentDTO(id, pageId, x, y, width, height, sequence, text);
	}

	@Override
	public Iterable<FragmentDTO> findAllByPage(Long pageId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("page_id", pageId)
				.build();
		// @formatter:on

		return find(where);
	}
}