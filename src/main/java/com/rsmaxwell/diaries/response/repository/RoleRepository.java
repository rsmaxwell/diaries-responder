package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.RoleDTO;
import com.rsmaxwell.diaries.response.model.Role;

public interface RoleRepository extends CrudRepository<Role, RoleDTO, Long> {

	Optional<RoleDTO> findByName(String name);

}