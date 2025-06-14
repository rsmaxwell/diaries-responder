package com.rsmaxwell.diaries.response.model;

import com.rsmaxwell.diaries.response.dto.PersonDTO;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@Table(name = "person")
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Person {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

	@NonNull
	@Column(name = "username", unique = true)
	private String username;

	@NonNull
	@Column(name = "passwordHash")
	private String passwordHash;

	@NonNull
	@Column(name = "firstName")
	private String firstName;

	@NonNull
	@Column(name = "lastName")
	private String lastName;

	@NonNull
	@Column(name = "knownas")
	private String knownas;

	@NonNull
	@Column(name = "email")
	private String email;

	@NonNull
	@Column(name = "countryCode")
	private Integer countryCode;

	@NonNull
	@Column(name = "nationalNumber")
	private Long nationalNumber;

	public Person(PersonDTO dto) {
		this.id = dto.getId();
		this.username = dto.getUsername();
		this.passwordHash = dto.getPasswordHash();
		this.firstName = dto.getFirstName();
		this.lastName = dto.getLastName();
		this.knownas = dto.getKnownas();
		this.email = dto.getEmail();
		this.countryCode = dto.getCountryCode();
		this.nationalNumber = dto.getNationalNumber();
	}
}
