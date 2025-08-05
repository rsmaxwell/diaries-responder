package com.rsmaxwell.diaries.response.repositoryImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.utilities.SqlBuilder;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

public class FragmentRepositoryImpl extends AbstractCrudRepository<Fragment, FragmentDBDTO, Long> implements FragmentRepository {

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
	public <S extends Fragment> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("version");
		list.add("sequence");
		list.add("year");
		list.add("month");
		list.add("day");
		list.add("text");
		return list;
	}

	@Override
	public <S extends Fragment> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getVersion());
		list.add(entity.getSequence());
		list.add(entity.getYear());
		list.add(entity.getMonth());
		list.add(entity.getDay());
		list.add(entity.getText());
		return list;
	}

	@Override
	public FragmentDBDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long version = getLongFromSqlResult(result, 1, null);
		BigDecimal sequence = getBigDecimalFromSqlResult(result, 2, null);
		Integer year = getIntegerFromSqlResult(result, 3, null);
		Integer month = getIntegerFromSqlResult(result, 4, null);
		Integer day = getIntegerFromSqlResult(result, 5, null);
		String text = getStringFromSqlResult(result, 6, null);

		//@formatter:off
		return FragmentDBDTO.builder()
				.id(id)
				.year(year)
				.month(month)
				.day(day)
				.sequence(sequence)
				.text(text)
				.version(version)
				.build();
		//@formatter:on
	}

	@Override
	public Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day) {

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

	@Override
	public Iterable<FragmentDBDTO> findAllWithoutMarquee() {

		// @formatter:off		
		String sql = SqlBuilder.create()
			    .select("f.id, f.year, f.month, f.day, f.sequence, f.text, f.version")
			    .from("fragment f")
			    .leftJoin("marquee m").on("f.id = m.fragment_id")
			    .whereIsNull("m.fragment_id")
			    .orderBy("f.year, f.month, f.day, f.sequence, f.id")
			    .build();
		// @formatter:on

		List<FragmentDBDTO> list = new ArrayList<FragmentDBDTO>();
		for (Object[] result : getResultList(sql.toString())) {
			FragmentDBDTO dto = newDTO(result);
			list.add(dto);
		}

		return list;
	}

	public Iterable<FragmentDBDTO> findAllMarqueesByFragmentDate(Integer year, Integer month, Integer day) throws Exception {

		// @formatter:off		
		String sql = SqlBuilder.create()
			    .select("m.*")
			    .from("marquee m")
			    .innerJoin("fragment f").on("m.fragment_id = f.id")
			    .whereFragmentDate(year, month, day)
			    .build();
		// @formatter:on

		List<FragmentDBDTO> list = new ArrayList<FragmentDBDTO>();
		for (Object[] result : getResultList(sql.toString())) {
			FragmentDBDTO dto = newDTO(result);
			list.add(dto);
		}

		return list;
	}
}
