package com.rsmaxwell.diaries.response.utilities;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.jpa.boot.spi.Bootstrap;

import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.Jdbc;
import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.model.Role;

import jakarta.persistence.EntityManagerFactory;

public class GetEntityManager {

	private static final Logger log = LogManager.getLogger(GetEntityManager.class);

	public static EntityManagerFactory factory(DbConfig dbConfig) {

		EntityManagerFactory entityManagerFactory = null;

		try {
			List<User> users = dbConfig.getUsers();
			if (users.size() <= 0) {
				throw new Exception("No users defined in configuration");
			}
			User user = users.get(0);
			String databaseName = dbConfig.getDatabase();

			entityManagerFactory = factory(user, databaseName, dbConfig);

		} catch (Throwable ex) {
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
		}

		return entityManagerFactory;
	}

	public static EntityManagerFactory adminFactory(DbConfig dbConfig) {

		EntityManagerFactory entityManagerFactory = null;

		try {
			User admin = dbConfig.getAdmin();
			String databaseName = dbConfig.getDatabase();

			entityManagerFactory = factory(admin, databaseName, dbConfig);

		} catch (Throwable ex) {
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
		}

		return entityManagerFactory;
	}

	public static EntityManagerFactory adminFactory(String database, DbConfig dbConfig) {

		EntityManagerFactory entityManagerFactory = null;

		try {
			User admin = dbConfig.getAdmin();

			entityManagerFactory = factory(admin, database, dbConfig);

		} catch (Throwable ex) {
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
		}

		return entityManagerFactory;
	}

	private static EntityManagerFactory factory(User user, String database, DbConfig dbConfig) {

		EntityManagerFactory entityManagerFactory = null;

		try {
			Jdbc jdbc = dbConfig.getJdbc();
			Map<String, String> additionalConnectionProperties = dbConfig.getAdditionalConnectionProperties();

			Properties props = new Properties();
			props.put("jakarta.persistence.jdbc.url", dbConfig.getJdbcUrl(database));
			props.put("jakarta.persistence.jdbc.driver", jdbc.getDriver());
			props.put("jakarta.persistence.jdbc.user", user.getUsername());
			props.put("jakarta.persistence.jdbc.password", user.getPassword());

			if (additionalConnectionProperties != null) {
				for (String key : additionalConnectionProperties.keySet()) {
					String value = additionalConnectionProperties.get(key);
					props.put(key, value);
				}
			}

			log.debug("Connection properties:");
			for (String key : props.stringPropertyNames()) {
				log.debug(String.format("    %s : %s", key, props.getProperty(key)));
			}

			PersistenceUnitInfoImpl info = new PersistenceUnitInfoImpl();
			info.addClasses(Diary.class.getName());
			info.addClasses(Page.class.getName());
			info.addClasses(Person.class.getName());
			info.addClasses(Role.class.getName());
			info.addClasses(Marquee.class.getName());
			info.addClasses(Fragment.class.getName());
			info.addClasses(Marquee.class.getName());

			entityManagerFactory = Bootstrap.getEntityManagerFactoryBuilder(info, props).build();

		} catch (Throwable ex) {
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
		}

		return entityManagerFactory;
	}
}
