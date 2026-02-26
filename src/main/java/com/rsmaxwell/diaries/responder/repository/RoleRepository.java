package com.rsmaxwell.diaries.responder.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.RoleDTO;
import com.rsmaxwell.diaries.responder.model.Role;

public interface RoleRepository extends CrudRepository<Role, RoleDTO, Long> {

	Optional<RoleDTO> findByName(String name);

}