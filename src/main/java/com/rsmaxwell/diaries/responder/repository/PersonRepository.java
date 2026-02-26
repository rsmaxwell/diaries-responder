package com.rsmaxwell.diaries.responder.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.PersonDTO;
import com.rsmaxwell.diaries.responder.model.Person;

public interface PersonRepository extends CrudRepository<Person, PersonDTO, Long> {

	Optional<PersonDTO> findByUsername(String username);
}