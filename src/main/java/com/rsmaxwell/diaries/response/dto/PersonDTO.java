package com.rsmaxwell.diaries.response.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PersonDTO {

	private Long id;
	private String username;
	private String passwordHash;
	private String firstName;
	private String lastName;
	private String knownas;
	private String email;
	private Integer countryCode;
	private Long nationalNumber;
}
