package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.model.Person;

public interface PersonRepository extends CrudRepository<Person, PersonDTO, Long> {

	Optional<PersonDTO> findByUsername(String username);
}