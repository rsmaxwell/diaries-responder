package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

public class FragmentRepositoryImpl extends AbstractCrudRepository<Fragment, FragmentDTO, Long> implements FragmentRepository {

	private static final Logger log = LogManager.getLogger(FragmentRepositoryImpl.class);

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
		list.add("year");
		list.add("month");
		list.add("day");
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
		list.add("year");
		list.add("month");
		list.add("day");
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
		list.add(entity.getYear());
		list.add(entity.getMonth());
		list.add(entity.getDay());
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
		list.add(dto.getYear());
		list.add(dto.getMonth());
		list.add(dto.getDay());
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
		Integer year = getIntegerFromSqlResult(result, 6, null);
		Integer month = getIntegerFromSqlResult(result, 7, null);
		Integer day = getIntegerFromSqlResult(result, 8, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 9, null);
		String text = getStringFromSqlResult(result, 10, null);
		return new FragmentDTO(id, pageId, x, y, width, height, year, month, day, sequence, text);
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
				.add("page_id", marqueeId)
				.build();
		// @formatter:on

		return find(where);
	}

	@Override
	protected String orderBy() {
		return "ORDER BY year, month, day, sequence, id";
	}

	@Override
	public int updateWithMarquee(Marquee marquee) throws Exception {
		String separator = "";
		StringBuilder assignments = new StringBuilder();
		List<String> fields = List.of("x", "y", "width", "height");
		List<Object> values = new ArrayList<Object>();
		values.add(marquee.getX());
		values.add(marquee.getY());
		values.add(marquee.getWidth());
		values.add(marquee.getHeight());

		for (int i = 0; i < fields.size(); i++) {
			String field = fields.get(i);
			Object value = values.get(i);
			assignments.append(separator);
			assignments.append(field).append(" = ").append(quote(value));
			separator = ", ";
		}

		String sql = String.format("update %s set %s where %s = %s", getTable(), assignments, getKeyField(), marquee.getId().toString());

		Query query = entityManager.createNativeQuery(sql);
		int count = query.executeUpdate();
		log.info(String.format("update --> count: %d", count));

		return count;
	}
}
