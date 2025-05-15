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
		list.add("marquee_id");
		list.add("text");
		list.add("sequence");
		return list;
	}

	@Override
	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("marquee_id");
		list.add("text");
		list.add("sequence");
		return list;
	}

	@Override
	public <S extends Fragment> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getMarquee().getId());
		list.add(entity.getText());
		list.add(entity.getSequence());
		return list;
	}

	@Override
	public <S extends FragmentDTO> List<Object> getDTOValues(S dto) {
		List<Object> list = new ArrayList<Object>();
		list.add(dto.getMarqueeId());
		list.add(dto.getSequence());
		list.add(dto.getText());
		return list;
	}

	@Override
	public FragmentDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long marqueeId = getLongFromSqlResult(result, 1, null);
		String text = getStringFromSqlResult(result, 2, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 3, null);
		return new FragmentDTO(id, marqueeId, text, sequence);
	}

	@Override
	public Iterable<FragmentDTO> findAllByPage(Long marqueeId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("marquee_id", marqueeId)
				.build();
		// @formatter:on

		return find(where);
	}

	@Override
	protected String orderBy() {
		return "ORDER BY sequence NULLS LAST, id";
	}
}
