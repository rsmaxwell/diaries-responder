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
		list.add("year");
		list.add("month");
		list.add("day");
		list.add("sequence");
		list.add("marquee_id");
		list.add("text");
		return list;
	}

	@Override
	public List<String> getDTOFields() {
		List<String> list = new ArrayList<String>();
		list.add("year");
		list.add("month");
		list.add("day");
		list.add("sequence");
		list.add("marquee_id");
		list.add("text");
		return list;
	}

	@Override
	public <S extends Fragment> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getYear());
		list.add(entity.getMonth());
		list.add(entity.getDay());
		list.add(entity.getSequence());
		list.add(entity.getMarquee().getId());
		list.add(entity.getText());
		return list;
	}

	@Override
	public <S extends FragmentDTO> List<Object> getDTOValues(S dto) {
		List<Object> list = new ArrayList<Object>();
		list.add(dto.getYear());
		list.add(dto.getMonth());
		list.add(dto.getDay());
		list.add(dto.getSequence());
		list.add(dto.getMarqueeId());
		list.add(dto.getText());
		return list;
	}

	@Override
	public FragmentDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Integer year = getIntegerFromSqlResult(result, 1, null);
		Integer month = getIntegerFromSqlResult(result, 2, null);
		Integer day = getIntegerFromSqlResult(result, 3, null);
		Long marqueeId = getLongFromSqlResult(result, 4, null);
		String text = getStringFromSqlResult(result, 5, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 6, null);
		return new FragmentDTO(id, year, month, day, sequence, marqueeId, text);
	}

	@Override
	public Iterable<FragmentDTO> findAllByDate(Integer year, Integer month, Integer day) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("year", year)
				.add("month", month)
				.add("day", day)
				.build();
		// @formatter:on

		return find(where);
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
		return "ORDER BY year, month, day, sequence, id";
	}
}
