package com.rsmaxwell.diaries.responder;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.response.utilities.GetEntityManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public class CreateTables {

	private static final Logger log = LogManager.getLogger(CreateTables.class);

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

		// @formatter:off
		try (EntityManagerFactory entityManagerFactory = GetEntityManager.adminFactory(dbConfig); 
			 EntityManager entityManager = entityManagerFactory.createEntityManager()) {
			// @formatter:on

		} catch (Exception e) {
			log.catching(e);
			return;
		}
	}
}
