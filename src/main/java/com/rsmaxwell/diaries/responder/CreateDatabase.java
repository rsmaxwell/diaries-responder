package com.rsmaxwell.diaries.responder;

import java.sql.Connection;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.User;

public class CreateDatabase {

	private static final Logger log = LogManager.getLogger(CreateDatabase.class);

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
		String database = dbConfig.getDatabase();

		try (Connection con = Database.connect(dbConfig)) {
			createDatabase(con, database);
			createUsers(con, dbConfig);
		}

		log.info("Success");
	}

	public static void createUsers(Connection con, DbConfig dbConfig) throws Exception {

		String database = dbConfig.getDatabase();

		for (User user : dbConfig.getUsers()) {
			String username = user.getUsername();
			String password = user.getPassword();
			createUser(con, username, password, database);

			Database.grantPrivilagesToUser(con, database, username);
		}
	}

	public static void createUser(Connection con, String username, String password, String database) throws Exception {

		boolean found = Database.userExists(con, username);

		if (found) {
			log.info(String.format("user '%s' already exists", username));
			return;
		}

		Database.createUser(con, username, password);
	}

	public static void createDatabase(Connection con, String database) throws Exception {

		boolean found = Database.databaseExists(con, database);

		if (found) {
			log.info(String.format("Database '%s' already exists", database));
			return;
		}

		Database.createDatabase(con, database);
	}
}
