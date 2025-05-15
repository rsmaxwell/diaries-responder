package com.rsmaxwell.diaries.response.utilities;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
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
	private MarqueeRepository marqueeRepository;
	private FragmentRepository fragmentRepository;
	private Integer refreshPeriod;
	private Integer refreshExpiration;
	private String secret;
	private DiariesConfig diaries;
	private MqttAsyncClient clientResponder;

}
