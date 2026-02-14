package com.rsmaxwell.diaries.responder;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.dto.RoleDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.RoleRepository;
import com.rsmaxwell.diaries.response.repositoryImpl.DiaryRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PageRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.RoleRepositoryImpl;
import com.rsmaxwell.diaries.response.template.ImageInfo;
import com.rsmaxwell.diaries.response.utilities.GetEntityManager;
import com.rsmaxwell.diaries.response.utilities.MyFileUtilities;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

public class SyncroniseDatabase {

	private static final Logger log = LogManager.getLogger(SyncroniseDatabase.class);
	static private ObjectMapper mapper = new ObjectMapper();

	private DiariesConfig diariesConfig;
	private DiaryRepository diaryRepository;
	private PageRepository pageRepository;
	private RoleRepository roleRepository;

	public SyncroniseDatabase(DiariesConfig diariesConfig, DiaryRepository diaryRepository, PageRepository pageRepository, RoleRepository roleRepository) {
		this.diariesConfig = diariesConfig;
		this.diaryRepository = diaryRepository;
		this.pageRepository = pageRepository;
		this.roleRepository = roleRepository;
	}

	static Option createOption(String shortName, String longName, String argName, String description, boolean required) {
		return Option.builder(shortName).longOpt(longName).argName(argName).desc(description).hasArg().required(required).build();
	}

	public static void main(String[] args) throws Exception {

		Option configOption = createOption("c", "config", "Configuration", "Configuration", true);

		// @formatter:off
		Options options = new Options();
		options.addOption(configOption);
		// @formatter:on

		CommandLineParser commandLineParser = new DefaultParser();
		CommandLine commandLine = commandLineParser.parse(options, args);

		String filename = commandLine.getOptionValue("config");
		Config config = Config.read(filename);
		DbConfig dbConfig = config.getDb();
		DiariesConfig diariesConfig = config.getDiaries();

		EntityTransaction tx = null;
		// @formatter:off
		try (EntityManagerFactory entityManagerFactory = GetEntityManager.adminFactory(dbConfig); 
			 EntityManager entityManager = entityManagerFactory.createEntityManager()) {
			// @formatter:on

			DiaryRepository diaryRepository = new DiaryRepositoryImpl(entityManager);
			PageRepository pageRepository = new PageRepositoryImpl(entityManager);
			RoleRepository roleRepository = new RoleRepositoryImpl(entityManager);
			SyncroniseDatabase p = new SyncroniseDatabase(diariesConfig, diaryRepository, pageRepository, roleRepository);

			tx = entityManager.getTransaction();
			tx.begin();

			p.synchroniseDiaries();
			p.synchroniseRoles();

			tx.commit();

			log.info("Success");

		} catch (Exception e) {
			log.catching(e);
			if (tx != null) {
				tx.rollback();
			}
			return;
		}
	}

	public void synchroniseDiaries() throws Exception {

		log.info("Refresh the diaries");

		Path root = Path.of(diariesConfig.getRoot());
		String diariesDirName = diariesConfig.getDiaries();
		if (root == null) {
			throw new Exception("Root directory not configured.");
		}
		if (diariesDirName == null) {
			throw new Exception("Diaries directory not configured.");
		}
		Path diariesDirPath = root.resolve(diariesDirName);
		File diariesDir = diariesDirPath.toFile();

		File[] children = diariesDir.listFiles(new FilenameFilter() {

			@Override
			public boolean accept(File f, String name) {
				if (!f.isDirectory()) {
					return false;
				}
				if (!name.startsWith("diary")) {
					return false;
				}
				return true;
			}
		});

		// Make sure every database Diary matches an original diary on the file system
		Iterable<DiaryDTO> diaries = diaryRepository.findAll();
		for (DiaryDTO diary : diaries) {
			String name = diary.getName();
			Path path = diariesDirPath.resolve(name);

			log.info(String.format("%s", name));

			if (!path.toFile().exists()) {
				String message = String.format("The database Diary '%s' does not have a corresponding directory", name);
				log.info(message);
				throw new Exception(message);
			}
		}

		// Make sure there is a database Diary for each original diary on the filesystem
		// Also synchronise the database pages with Pages on the file system
		for (File diarydir : children) {
			String name = diarydir.getName();
			Optional<DiaryDTO> optional = diaryRepository.findByName(diarydir.getName());

			if (optional.isEmpty()) {
				log.info(String.format("creating database Diary '%s' to correspond with the filesystem directory", name));

				BigDecimal sequence = new BigDecimal(1);
				diaryRepository.save(new Diary(name, sequence));
			}

			synchronisePages(diarydir);
		}
	}

	public void synchronisePages(File diaryDir) throws Exception {

		log.info("Refresh the pages");

		String root = diariesConfig.getRoot();
		String diaryName = MyFileUtilities.getFileName(diaryDir.getName());

		Optional<DiaryDTO> optionalDiary = diaryRepository.findByName(diaryName);
		if (optionalDiary.isEmpty()) {
			throw new Exception(String.format("Diary '%s' not found in database"));
		}
		DiaryDTO diaryDTO = optionalDiary.get();

		// Make sure every database Page matches an original image file
		Iterable<PageDTO> pages = pageRepository.findAllByDiary(diaryDTO.getId());
		for (PageDTO page : pages) {
			String pageName = page.getName();
			File imageFile = Paths.get(root, diaryName, String.format("%s.jpg", pageName)).toFile();

			if (!imageFile.exists()) {
				throw new Exception(String.format("The database Page '%s/%s' does not match an original image file", diaryName, pageName));
			}
		}

		// Find the names of the original image files for this diary
		File[] imageFiles = diaryDir.listFiles(new FileFilter() {

			@Override
			public boolean accept(File f) {

				if (!f.isFile()) {
					return false;
				}

				String name = f.getName();

				if (!name.startsWith("img")) {
					return false;
				}
				if (!name.endsWith(".jpg")) {
					return false;
				}
				return true;
			}
		});

		// Make sure there is a Page entry in the database for each original image file
		//
		// http://localhost:8081/images/diary-1837/img2556.jpg
		// String baseUrl = "http://localhost:8081/image";
		for (File imageFile : imageFiles) {

			String fileName = imageFile.getName();
			String pageName = MyFileUtilities.getFileName(fileName);
			String pageExtension = MyFileUtilities.getFileExtension(fileName);
			BigDecimal sequence = new BigDecimal(1);
			Long version = 0L;

			ImageInfo info = getImageInfo(imageFile);

			//@formatter:off
			PageDTO fsPageDTO = PageDTO.builder()
					.id(0L)
					.diaryId(diaryDTO.getId())
					.name(pageName)
					.sequence(sequence)
					.extension(pageExtension)
					.width(info.getWidth())
				    .height(info.getHeight())
				    .version(version)
				    .build();
			//@formatter:on

			Optional<DiaryDTO> optionalDiary2 = diaryRepository.findById(diaryDTO.getId());
			if (optionalDiary2.isEmpty()) {
				throw new Exception(String.format("Could not find diary with id: %d", diaryDTO.getId()));
			}
			Diary diary = new Diary(diaryDTO);
			Page fsPage = new Page(diary, fsPageDTO);

			Optional<PageDTO> optionalPage = pageRepository.findByDiaryAndName(diaryDTO.getId(), pageName);
			if (optionalPage.isEmpty()) {
				log.info(String.format("Creating new DbPage '%s/%s' to match the filesystem directory", diaryName, pageName));
				pageRepository.save(fsPage);
			} else {
				PageDTO dbPageDTO = optionalPage.get();

				if (fsPageDTO.equalsExcludingIdAndVersion(dbPageDTO)) {
					log.info(String.format("DbPage matches the filesystemPage. Nothing to do: '%s/%s'", diaryName, pageName));
				} else {
					log.info(String.format("DbPage does not match the filesystemPage. Updating the dbPage: '%s/%s'", diaryName, pageName));

					log.info(String.format("fsPage: %s", mapper.writeValueAsString(fsPageDTO)));
					log.info(String.format("dbPage: %s", mapper.writeValueAsString(dbPageDTO)));

					Page dbPage = new Page(diary, dbPageDTO);

					dbPage.updateFrom(fsPage);
					pageRepository.update(dbPage);
				}
			}
		}
	}

	private ImageInfo getImageInfo(File imageFile) throws Exception {

		ImageInfo info = new ImageInfo();

		try (ImageInputStream input = ImageIO.createImageInputStream(imageFile)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new Exception(String.format("No ImageReader found for the file: %s", imageFile.getAbsoluteFile()));
			}

			ImageReader reader = readers.next();
			reader.setInput(input);
			info.setHeight(reader.getHeight(0));
			info.setWidth(reader.getWidth(0));
			reader.dispose();

		} catch (IOException e) {
			System.err.println("Failed to read image dimensions: " + e.getMessage());
		}

		return info;
	}

	public void synchroniseRoles() throws Exception {

		log.info("Refresh the roles");

		List<String> list = new ArrayList<String>();
		list.add("admin");
		list.add("editor");
		list.add("viewer");

		for (String name : list) {
			Optional<RoleDTO> optional = roleRepository.findByName(name);

			if (optional.isPresent()) {
				log.info(String.format("Role '%s' already has a database record", name));
			} else {
				log.info(String.format("creating Role '%s' database record", name));
				// diaryRepository.save(new Diary(name));
			}
		}

		Iterable<RoleDTO> roles = roleRepository.findAll();
		for (RoleDTO role : roles) {
			String name = role.getName();

			if (list.contains(name)) {
				log.info(String.format("The database Role '%s' is correct", name));
			} else {
				String message = String.format("The database Role '%s' sould not be present", name);
				log.info(message);
				throw new Exception(message);
			}
		}
	}
}
