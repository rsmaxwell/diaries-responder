
package com.rsmaxwell.diaries.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
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
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.dto.PersonDTO;
import com.rsmaxwell.diaries.response.dto.RoleDTO;
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

		Person p0 = new Person("007", "secrethash", "James", "Bond", "007", "bond@mi6.uk.gov", 44, 56220218978L);
		Person p1 = new Person("horrorpoplar", "maze", "Ayana", "Bush", "bob", "bobhorror@acer.com", 44, 54990891104L);
		Person p2 = new Person("swarmbreath", "architect", "Frederick", "Costa", "jill", "jgswarm@@london.edu.uk", 44, 12190125697L);
		Person p3 = new Person("sickowither", "direct", "Brian", "Villa", "greg", "gyobbo@os.co.uk", 44, 46431927722L);
		Person p4 = new Person("pushprovision", "landowner", "Evelyn", "Benton", "top", "beefsteak@waitrose.co.uk", 44, 50782257157L);
		Person p5 = new Person("fantasy", "quarter", "Jazlynn", "Collins", "toby", "thomashall@ntlworld.co.uk", 44, 53377002182L);
		Person p6 = new Person("shine", "conductor", "Sean", "Pierce", "sue", "qwerty@outlook.com", 44, 42326833933L);
		Person p7 = new Person("indulge", "action", "Gisselle", "Moss", "tom", "gross@hotmail.com", 44, 39906867554L);
		Person p8 = new Person("hemisphere", "horseshoe", "Cherish", "Nguyen", "georgy", "george@hotmail.com", 44, 35598005196L);
		Person p9 = new Person("judicial", "sigh", "Tianna ", "Meza", "fred", "fredbloggs@vista.co.uk", 44, 35598005196L);

		Long id0 = repository.save(p0);
		Long id1 = repository.save(p1);
		Long id2 = repository.save(p2);
		Long id3 = repository.save(p3);
		Long id4 = repository.save(p4);
		Long id5 = repository.save(p5);
		Long id6 = repository.save(p6);
		Long id7 = repository.save(p7);
		assertEquals(8, repository.count());

		Long id8 = repository.save(p8);
		Long id9 = repository.save(p9);

		int count1 = 0;
		Iterable<PersonDTO> all = repository.findAll();
		for (PersonDTO p : all) {
			Long personId = p.getId();
			Optional<PersonDTO> optionalPersonDTO = repository.findById(personId);
			if (optionalPersonDTO.isEmpty()) {
				fail("Person not found: id: " + personId);
			}
			PersonDTO dto = optionalPersonDTO.get();
			Person person2 = new Person(dto);

			assertEquals(p.getId(), person2.getId());
			assertTrue(p.equals(dto));

			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		assertTrue(repository.existsById(id1));
		repository.delete(p1);
		assertFalse(repository.existsById(id1));
		assertEquals(repository.count(), 9);

		repository.deleteAll();
		assertEquals(repository.count(), 0);
	}

	@SuppressWarnings("unused")
	@Test
	void testDiaryRepository() throws Exception {

		DiaryRepository repository = new DiaryRepositoryImpl(entityManager);
		repository.deleteAll();

		Diary d0 = new Diary("hardship");
		Diary d1 = new Diary("horrorpoplar");
		Diary d2 = new Diary("swarmbreath");
		Diary d3 = new Diary("sickowither");
		Diary d4 = new Diary("pushprovision");
		Diary d5 = new Diary("fantasy");
		Diary d6 = new Diary("shine");
		Diary d7 = new Diary("indulge");

		Long id0 = repository.save(d0);
		Long id1 = repository.save(d1);
		Long id2 = repository.save(d2);
		Long id3 = repository.save(d3);
		Long id4 = repository.save(d4);
		Long id5 = repository.save(d5);
		Long id6 = repository.save(d6);
		Long id7 = repository.save(d7);
		assertEquals(8, repository.count());

		List<Diary> extra = new ArrayList<Diary>();
		Diary d8 = new Diary("hemisphere");
		Diary d9 = new Diary("judicial");

		Long id8 = repository.save(d8);
		Long id9 = repository.save(d9);

		int count1 = 0;
		Iterable<DiaryDTO> all = repository.findAll();
		for (DiaryDTO p : all) {
			Optional<DiaryDTO> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			DiaryDTO p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));

			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		assertEquals(repository.count(), 10);
		assertTrue(repository.existsById(id0));
		repository.delete(d0);
		assertFalse(repository.existsById(id0));
		assertEquals(repository.count(), 9);

		assertTrue(repository.existsById(id1));
		repository.delete(d1);
		assertFalse(repository.existsById(id1));
		assertEquals(repository.count(), 8);

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

		Diary d0 = new Diary("hardship");
		Diary d1 = new Diary("horrorpoplar");
		Diary d2 = new Diary("swarmbreath");

		Long diaryId0 = diaryRepository.save(d0);
		Long diaryId1 = diaryRepository.save(d1);
		Long diaryId2 = diaryRepository.save(d2);
		assertEquals(3, diaryRepository.count());

		BigDecimal sequence = new BigDecimal(1);

		Long p0 = pageRepository.save(new Page(d0, "structure", sequence, "jpg", 123, 456));
		Long p1 = pageRepository.save(new Page(d0, "deficit", sequence, "jpg", 123, 456));
		Long p2 = pageRepository.save(new Page(d0, "asset", sequence, "jpg", 123, 456));

		Long p3 = pageRepository.save(new Page(d1, "intermediate", sequence, "jpg", 123, 456));
		Long p4 = pageRepository.save(new Page(d1, "calendar", sequence, "jpg", 123, 456));
		Long p5 = pageRepository.save(new Page(d1, "body", sequence, "jpg", 123, 456));

		Long p6 = pageRepository.save(new Page(d2, "basin", sequence, "jpg", 123, 456));
		Long p7 = pageRepository.save(new Page(d2, "deal", sequence, "jpg", 123, 456));
		Long p8 = pageRepository.save(new Page(d2, "promotion", sequence, "jpg", 123, 456));
		assertEquals(9, pageRepository.count());

		Iterable<PageDTO> pages = pageRepository.findAllByDiary(d1.getId());
		List<PageDTO> list = new ArrayList<PageDTO>();
		for (PageDTO page : pages) {
			list.add(page);
		}
		assertEquals(3, list.size());

		Optional<PageDTO> optionalPage1 = pageRepository.findByDiaryAndName(d1.getId(), "calendar");
		assertTrue(optionalPage1.isPresent());

		Optional<PageDTO> optionalPage2 = pageRepository.findByDiaryAndName(d1.getId(), "junk");
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

		Role r0 = new Role("hardship");
		Role r1 = new Role("horrorpoplar");
		Role r2 = new Role("swarmbreath");
		Role r3 = new Role("sickowither");
		Role r4 = new Role("pushprovision");
		Role r5 = new Role("fantasy");
		Role r6 = new Role("shine");
		Role r7 = new Role("indulge");

		Long id0 = repository.save(r0);
		Long id1 = repository.save(r1);
		Long id2 = repository.save(r2);
		Long id3 = repository.save(r3);
		Long id4 = repository.save(r4);
		Long id5 = repository.save(r5);
		Long id6 = repository.save(r6);
		Long id7 = repository.save(r7);
		assertEquals(8, repository.count());

		Role r8 = new Role("hemisphere");
		Role r9 = new Role("judicial");
		Long id8 = repository.save(r8);
		Long id9 = repository.save(r9);

		int count1 = 0;
		Iterable<RoleDTO> all = repository.findAll();
		for (RoleDTO p : all) {
			Optional<RoleDTO> y = repository.findById(p.getId());

			assertNotNull(y.isPresent());
			RoleDTO p2 = y.get();

			assertEquals(p.getId(), p2.getId());
			assertTrue(p.equals(p2));

			count1++;
		}

		assertEquals(count1, 10);
		assertEquals(count1, repository.count());

		assertEquals(repository.count(), 10);
		assertTrue(repository.existsById(id0));
		repository.delete(r0);
		assertFalse(repository.existsById(id0));
		assertEquals(repository.count(), 9);

		assertTrue(repository.existsById(id1));
		repository.delete(r1);
		assertFalse(repository.existsById(id1));
		assertEquals(repository.count(), 8);

		repository.deleteAll();
		assertEquals(repository.count(), 0);
	}
}
