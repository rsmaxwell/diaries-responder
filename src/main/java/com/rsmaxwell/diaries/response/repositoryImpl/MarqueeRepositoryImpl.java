package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.rsmaxwell.diaries.response.dto.MarqueeDTO;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class MarqueeRepositoryImpl extends AbstractCrudRepository<Marquee, MarqueeDTO, Long> implements MarqueeRepository {

	public MarqueeRepositoryImpl(EntityManager entityManager) {
		super(entityManager);
	}

	@Override
	public String getTable() {
		return "marquee";
	}

	@Override
	public <S extends Marquee> String getKeyValue(S entity) {
		return entity.getId().toString();
	}

	@Override
	public <S extends MarqueeDTO> String getDTOKeyValue(S dto) {
		return dto.getId().toString();
	}

	@Override
	public <S extends Marquee> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public <S extends MarqueeDTO> void setDTOKeyValue(S dto, Object value) {
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
		return list;
	}

	@Override
	public <S extends Marquee> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getPage().getId());
		list.add(entity.getX());
		list.add(entity.getY());
		list.add(entity.getWidth());
		list.add(entity.getHeight());
		list.add(entity.getSequence());
		return list;
	}

	@Override
	public <S extends MarqueeDTO> List<Object> getDTOValues(S dto) {
		List<Object> list = new ArrayList<Object>();
		list.add(dto.getPageId());
		list.add(dto.getX());
		list.add(dto.getY());
		list.add(dto.getWidth());
		list.add(dto.getHeight());
		list.add(dto.getSequence());
		return list;
	}

	@Override
	public MarqueeDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long pageId = getLongFromSqlResult(result, 1, null);
		Double x = getDoubleFromSqlResult(result, 2, null);
		Double y = getDoubleFromSqlResult(result, 3, null);
		Double width = getDoubleFromSqlResult(result, 4, null);
		Double height = getDoubleFromSqlResult(result, 5, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 6, null);
		return new MarqueeDTO(id, pageId, x, y, width, height, sequence);
	}

	@Override
	public Iterable<MarqueeDTO> findAllByPage(Long marqueeId) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("page_id", marqueeId)
				.build();
		// @formatter:on

		return find(where);
	}

	@Override
	protected String orderBy() {
		return "ORDER BY sequence NULLS LAST, id";
	}
}
