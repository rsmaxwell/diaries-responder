
package com.rsmaxwell.diaries.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.model.Person;
import com.rsmaxwell.diaries.response.model.Role;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.repository.RoleRepository;
import com.rsmaxwell.diaries.response.repositoryImpl.DiaryRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PageRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PersonRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.RoleRepositoryImpl;
import com.rsmaxwell.diaries.response.utilities.GetEntityManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

public class RepositoryTest {

	private static EntityManagerFactory entityManagerFactory;
	private static EntityManager entityManager;
	private static EntityTransaction tx;

	@BeforeAll
	static void overallSetup() {

		String home = System.getProperty("user.home");
		Path filePath = Paths.get(home, ".diaries", "test.json");
		String filename = filePath.toString();

		try {
			Config config = Config.read(filename);
			DbConfig dbConfig = config.getDb();
			entityManagerFactory = GetEntityManager.adminFactory("test", dbConfig);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@BeforeEach
	void testSetup() {
		try {
			entityManager = entityManagerFactory.createEntityManager();

			tx = entityManager.getTransaction();
			tx.begin();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@AfterEach
	void testTeardown() {
		try {
			if (entityManager != null) {

				tx = entityManager.getTransaction();
				tx.commit();

				entityManager.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@AfterAll
	static void overallTeardown() {
		try {
			if (entityManagerFactory != null) {

				entityManagerFactory.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	@Test
	void testPersonRepository() throws Exception {

		PersonRepository repository = new PersonRepositoryImpl(entityManager);
		repository.deleteAll();

		Person x0 = repository.save(new Person("007", "secrethash", "James", "Bond", "007", "bond@mi6.uk.gov", 44, 56220218978L));
		Person x1 = repository.save(new Person("horrorpoplar", "maze", "Ayana", "Bush", "bob", "bobhorror@acer.com", 44, 54990891104L));
		Person x2 = repository.save(new Person("swarmbreath", "architect", "Frederick", "Costa", "jill", "jgswarm@@london.edu.uk", 44, 12190125697L));
		Person x3 = repository.save(new Person("sickowither", "direct", "Brian", "Villa", "greg", "gyobbo@os.co.uk", 44, 46431927722L));
		Person x4 = repository.save(new Person("pushprovision", "landowner", "Evelyn", "Benton", "top", "beefsteak@waitrose.co.uk", 44, 50782257157L));
		Person x5 = repository.save(new Person("fantasy", "quarter", "Jazlynn", "Collins", "toby", "thomashall@ntlworld.co.uk", 44, 53377002182L));
		Person x6 = repository.save(new Person("shine", "conductor", "Sean", "Pierce", "sue", "qwerty@outlook.com", 44, 42326833933L));
		Person x7 = repository.save(new Person("indulge", "action", "Gisselle", "Moss", "tom", "gross@hotmail.com", 44, 39906867554L));
		assertEquals(8, repository.count());

		List<Person> extra = new ArrayList<Person>();
		extra.add(new Person("hemisphere", "horseshoe", "Cherish", "Nguyen", "georgy", "george@hotmail.com", 44, 35598005196L));
		extra.add(new Person("judicial", "sigh", "Tianna ", "Meza", "fred", "fredbloggs@vista.co.uk", 44, 35598005196L));

		List<Person> output = new ArrayList<Person>();
		Iterable<Person> result = repository.saveAll(extra);
		for (Person p : result) {
			output.add(p);
		}
		assertEquals(output.size(), 2);
		Person x8 = output.get(0);
		Person x9 = output.get(1);

		int count1 = 0;
		List<Long> ids = new ArrayList<Long>();
		Iterable<PersonDTO> all = repository.findAll();
		for (PersonDTO p : all) {
			Optional<PersonDTO> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			PersonDTO p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));

			ids.add(p.getId());
			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		int count2 = 0;
		Iterable<PersonDTO> more = repository.findById(ids);
		for (PersonDTO p : more) {
			Optional<PersonDTO> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			PersonDTO p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));
			count2++;
		}

		assertEquals(count2, 10);

		assertEquals(repository.count(), 10);
		assertTrue(repository.existsById(x0.getId()));
		repository.deleteById(x0.getId());
		assertFalse(repository.existsById(x0.getId()));
		assertEquals(repository.count(), 9);

		assertTrue(repository.existsById(x1.getId()));
		repository.delete(x1);
		assertFalse(repository.existsById(x1.getId()));
		assertEquals(repository.count(), 8);

		List<Person> entities = new ArrayList<Person>();
		entities.add(x2);
		entities.add(x3);

		assertTrue(repository.existsById(x2.getId()));
		assertTrue(repository.existsById(x3.getId()));
		repository.deleteAll(entities);
		assertFalse(repository.existsById(x2.getId()));
		assertFalse(repository.existsById(x3.getId()));
		assertEquals(repository.count(), 6);

		List<Long> listOfIds = new ArrayList<Long>();
		listOfIds.add(x4.getId());
		listOfIds.add(x5.getId());

		assertTrue(repository.existsById(x4.getId()));
		assertTrue(repository.existsById(x5.getId()));
		repository.deleteAllById(listOfIds);
		assertFalse(repository.existsById(x4.getId()));
		assertFalse(repository.existsById(x5.getId()));
		assertEquals(repository.count(), 4);

		List<Person> listOfEntities = new ArrayList<Person>();
		listOfEntities.add(x6);
		listOfEntities.add(x7);

		assertTrue(repository.existsById(x6.getId()));
		assertTrue(repository.existsById(x7.getId()));
		repository.deleteAll(listOfEntities);
		assertFalse(repository.existsById(x6.getId()));
		assertFalse(repository.existsById(x7.getId()));
		assertEquals(repository.count(), 2);

		repository.deleteAll();
		assertEquals(repository.count(), 0);
	}

	@SuppressWarnings("unused")
	@Test
	void testDiaryRepository() throws Exception {

		DiaryRepository repository = new DiaryRepositoryImpl(entityManager);
		repository.deleteAll();

		Diary x0 = repository.save(new Diary("hardship"));
		Diary x1 = repository.save(new Diary("horrorpoplar"));
		Diary x2 = repository.save(new Diary("swarmbreath"));
		Diary x3 = repository.save(new Diary("sickowither"));
		Diary x4 = repository.save(new Diary("pushprovision"));
		Diary x5 = repository.save(new Diary("fantasy"));
		Diary x6 = repository.save(new Diary("shine"));
		Diary x7 = repository.save(new Diary("indulge"));
		assertEquals(8, repository.count());

		List<Diary> extra = new ArrayList<Diary>();
		extra.add(new Diary("hemisphere"));
		extra.add(new Diary("judicial"));

		List<Diary> output = new ArrayList<Diary>();
		Iterable<Diary> result = repository.saveAll(extra);
		for (Diary p : result) {
			output.add(p);
		}
		assertEquals(output.size(), 2);
		Diary x8 = output.get(0);
		Diary x9 = output.get(1);

		int count1 = 0;
		List<Long> ids = new ArrayList<Long>();
		Iterable<Diary> all = repository.findAll();
		for (Diary p : all) {
			Optional<Diary> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			Diary p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));

			ids.add(p.getId());
			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		int count2 = 0;
		Iterable<Diary> more = repository.findById(ids);
		for (Diary p : more) {
			Optional<Diary> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			Diary p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));
			count2++;
		}

		assertEquals(count2, 10);

		assertEquals(repository.count(), 10);
		assertTrue(repository.existsById(x0.getId()));
		repository.deleteById(x0.getId());
		assertFalse(repository.existsById(x0.getId()));
		assertEquals(repository.count(), 9);

		assertTrue(repository.existsById(x1.getId()));
		repository.delete(x1);
		assertFalse(repository.existsById(x1.getId()));
		assertEquals(repository.count(), 8);

		List<Diary> entities = new ArrayList<Diary>();
		entities.add(x2);
		entities.add(x3);

		assertTrue(repository.existsById(x2.getId()));
		assertTrue(repository.existsById(x3.getId()));
		repository.deleteAll(entities);
		assertFalse(repository.existsById(x2.getId()));
		assertFalse(repository.existsById(x3.getId()));
		assertEquals(repository.count(), 6);

		List<Long> listOfIds = new ArrayList<Long>();
		listOfIds.add(x4.getId());
		listOfIds.add(x5.getId());

		assertTrue(repository.existsById(x4.getId()));
		assertTrue(repository.existsById(x5.getId()));
		repository.deleteAllById(listOfIds);
		assertFalse(repository.existsById(x4.getId()));
		assertFalse(repository.existsById(x5.getId()));
		assertEquals(repository.count(), 4);

		List<Diary> listOfEntities = new ArrayList<Diary>();
		listOfEntities.add(x6);
		listOfEntities.add(x7);

		assertTrue(repository.existsById(x6.getId()));
		assertTrue(repository.existsById(x7.getId()));
		repository.deleteAll(listOfEntities);
		assertFalse(repository.existsById(x6.getId()));
		assertFalse(repository.existsById(x7.getId()));
		assertEquals(repository.count(), 2);

		repository.deleteAll();
		assertEquals(repository.count(), 0);
	}

	@SuppressWarnings("unused")
	@Test
	void testPageRepository() throws Exception {

		PageRepository pageRepository = new PageRepositoryImpl(entityManager);
		pageRepository.deleteAll();

		DiaryRepository diaryRepository = new DiaryRepositoryImpl(entityManager);
		diaryRepository.deleteAll();

		Diary x0 = diaryRepository.save(new Diary("hardship"));
		Diary x1 = diaryRepository.save(new Diary("horrorpoplar"));
		Diary x2 = diaryRepository.save(new Diary("swarmbreath"));
		assertEquals(3, diaryRepository.count());

		Page y0 = pageRepository.save(new Page(x0, "structure", "jpg", 123, 456));
		Page y1 = pageRepository.save(new Page(x0, "deficit", "jpg", 123, 456));
		Page y2 = pageRepository.save(new Page(x0, "asset", "jpg", 123, 456));

		Page y3 = pageRepository.save(new Page(x1, "intermediate", "jpg", 123, 456));
		Page y4 = pageRepository.save(new Page(x1, "calendar", "jpg", 123, 456));
		Page y5 = pageRepository.save(new Page(x1, "body", "jpg", 123, 456));

		Page y6 = pageRepository.save(new Page(x2, "basin", "jpg", 123, 456));
		Page y7 = pageRepository.save(new Page(x2, "deal", "jpg", 123, 456));
		Page y8 = pageRepository.save(new Page(x2, "promotion", "jpg", 123, 456));
		assertEquals(9, pageRepository.count());

		Iterable<PageDTO> pages = pageRepository.findAllByDiary(x1);
		List<PageDTO> list = new ArrayList<PageDTO>();
		for (PageDTO page : pages) {
			list.add(page);
		}
		assertEquals(3, list.size());

		Optional<PageDTO> optionalPage1 = pageRepository.findByDiaryAndName(x1, "calendar");
		assertTrue(optionalPage1.isPresent());

		Optional<PageDTO> optionalPage2 = pageRepository.findByDiaryAndName(x1, "junk");
		assertTrue(optionalPage2.isEmpty());

		pageRepository.deleteAll();
		assertEquals(pageRepository.count(), 0);

		diaryRepository.deleteAll();
		assertEquals(diaryRepository.count(), 0);
	}

	@SuppressWarnings("unused")
	@Test
	void testRoleRepository() throws Exception {

		RoleRepository repository = new RoleRepositoryImpl(entityManager);
		repository.deleteAll();

		Role x0 = repository.save(new Role("hardship"));
		Role x1 = repository.save(new Role("horrorpoplar"));
		Role x2 = repository.save(new Role("swarmbreath"));
		Role x3 = repository.save(new Role("sickowither"));
		Role x4 = repository.save(new Role("pushprovision"));
		Role x5 = repository.save(new Role("fantasy"));
		Role x6 = repository.save(new Role("shine"));
		Role x7 = repository.save(new Role("indulge"));
		assertEquals(8, repository.count());

		List<Role> extra = new ArrayList<Role>();
		extra.add(new Role("hemisphere"));
		extra.add(new Role("judicial"));

		List<Role> output = new ArrayList<Role>();
		Iterable<Role> result = repository.saveAll(extra);
		for (Role p : result) {
			output.add(p);
		}
		assertEquals(output.size(), 2);
		Role x8 = output.get(0);
		Role x9 = output.get(1);

		int count1 = 0;
		List<Long> ids = new ArrayList<Long>();
		Iterable<Role> all = repository.findAll();
		for (Role p : all) {
			Optional<Role> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			Role p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));

			ids.add(p.getId());
			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		int count2 = 0;
		Iterable<Role> more = repository.findById(ids);
		for (Role p : more) {
			Optional<Role> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			Role p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));
			count2++;
		}

		assertEquals(count2, 10);

		assertEquals(repository.count(), 10);
		assertTrue(repository.existsById(x0.getId()));
		repository.deleteById(x0.getId());
		assertFalse(repository.existsById(x0.getId()));
		assertEquals(repository.count(), 9);

		assertTrue(repository.existsById(x1.getId()));
		repository.delete(x1);
		assertFalse(repository.existsById(x1.getId()));
		assertEquals(repository.count(), 8);

		List<Role> entities = new ArrayList<Role>();
		entities.add(x2);
		entities.add(x3);

		assertTrue(repository.existsById(x2.getId()));
		assertTrue(repository.existsById(x3.getId()));
		repository.deleteAll(entities);
		assertFalse(repository.existsById(x2.getId()));
		assertFalse(repository.existsById(x3.getId()));
		assertEquals(repository.count(), 6);

		List<Long> listOfIds = new ArrayList<Long>();
		listOfIds.add(x4.getId());
		listOfIds.add(x5.getId());

		assertTrue(repository.existsById(x4.getId()));
		assertTrue(repository.existsById(x5.getId()));
		repository.deleteAllById(listOfIds);
		assertFalse(repository.existsById(x4.getId()));
		assertFalse(repository.existsById(x5.getId()));
		assertEquals(repository.count(), 4);

		List<Role> listOfEntities = new ArrayList<Role>();
		listOfEntities.add(x6);
		listOfEntities.add(x7);

		assertTrue(repository.existsById(x6.getId()));
		assertTrue(repository.existsById(x7.getId()));
		repository.deleteAll(listOfEntities);
		assertFalse(repository.existsById(x6.getId()));
		assertFalse(repository.existsById(x7.getId()));
		assertEquals(repository.count(), 2);

		repository.deleteAll();
		assertEquals(repository.count(), 0);
	}
}
