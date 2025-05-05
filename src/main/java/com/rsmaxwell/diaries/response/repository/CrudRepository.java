package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

public interface CrudRepository<T, DTO, ID> {

	long count();

	void delete(T entity);

	void deleteAll();

	void deleteAll(Iterable<? extends T> entities);

	void deleteAllById(Iterable<? extends ID> ids);

	void deleteById(ID id);

	boolean existsById(ID id);

	Iterable<DTO> findAll();

	Iterable<DTO> findById(Iterable<ID> ids);

	Iterable<DTO> find(String wheree);

	Optional<DTO> findById(ID id);

	<S extends T> S save(S entity) throws Exception;

	<S extends DTO> S saveDTO(S entity) throws Exception;

	<S extends T> S update(S entity) throws Exception;

	<S extends DTO> S updateDTO(S entity) throws Exception;

	<S extends T> Iterable<S> saveAll(Iterable<S> entities) throws Exception;
}
