package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

public interface CrudRepository<T, DTO, ID> {

	long count();

	int delete(T entity);

	int deleteAll();

	boolean existsById(ID id);

	ID getId(Object value) throws Exception;

	Iterable<DTO> findAll();

	Iterable<DTO> find(String wheree);

	Optional<DTO> findById(ID id);

	<S extends T> ID save(S entity) throws Exception;

	<S extends T> int update(S entity) throws Exception;

	int deleteById(ID id);
}
