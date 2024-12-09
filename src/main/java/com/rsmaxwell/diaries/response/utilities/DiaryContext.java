package com.rsmaxwell.diaries.response.utilities;

import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.PersonRepository;

import jakarta.persistence.EntityManager;
import lombok.Data;

@Data
public class DiaryContext {

	private EntityManager entityManager;
	private DiaryRepository diaryRepository;
	private PageRepository pageRepository;
	private PersonRepository personRepository;
	private Integer refreshPeriod;
	private Integer refreshExpiration;
	private String secret;
	private DiariesConfig diaries;

}
