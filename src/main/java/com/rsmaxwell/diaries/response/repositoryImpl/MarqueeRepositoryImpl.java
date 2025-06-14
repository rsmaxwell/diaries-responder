package com.rsmaxwell.diaries.response.repositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.utilities.WhereBuilder;

import jakarta.persistence.EntityManager;

public class MarqueeRepositoryImpl extends AbstractCrudRepository<Marquee, MarqueeDBDTO, Long> implements MarqueeRepository {

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
	public <S extends Marquee> void setKeyValue(S entity, Object value) {
		entity.setId((Long) value);
	}

	@Override
	public String getKeyField() {
		return "id";
	}

	@Override
	public List<String> getFields() {
		List<String> list = new ArrayList<String>();
		list.add("fragment_id");
		list.add("x");
		list.add("y");
		list.add("width");
		list.add("height");
		return list;
	}

	@Override
	public <S extends Marquee> List<Object> getValues(S entity) {
		List<Object> list = new ArrayList<Object>();
		list.add(entity.getFragment().getId());
		list.add(entity.getX());
		list.add(entity.getY());
		list.add(entity.getWidth());
		list.add(entity.getHeight());
		return list;
	}

	@Override
	public MarqueeDBDTO newDTO(Object[] result) {
		Long id = getLongFromSqlResult(result, 0, null);
		Long fragmentId = getLongFromSqlResult(result, 1, null);
		Double x = getDoubleFromSqlResult(result, 2, null);
		Double y = getDoubleFromSqlResult(result, 3, null);
		Double width = getDoubleFromSqlResult(result, 4, null);
		Double height = getDoubleFromSqlResult(result, 5, null);
		return new MarqueeDBDTO(id, fragmentId, x, y, width, height);
	}

	@Override
	public Iterable<MarqueeDBDTO> findByFragment(Fragment fragment) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("fragment_id", fragment.getId())
				.build();
		// @formatter:on

		return find(where);
	}

	@Override
	public Optional<MarqueeDBDTO> findByFragmentId(Long id) {

		// @formatter:off
		String where = new WhereBuilder()
				.add("fragment_id", id)
				.build();
		// @formatter:on

		List<MarqueeDBDTO> list = new ArrayList<MarqueeDBDTO>();
		for (MarqueeDBDTO dto : find(where)) {
			list.add(dto);
		}

		return singleItem(list);

	}
}
