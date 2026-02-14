package com.rsmaxwell.diaries.responder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rsmaxwell.diaries.common.config.DbConfig;

public class Database {

	private static final Logger log = LogManager.getLogger(Database.class);

	public static Connection connect(DbConfig dbConfig) throws SQLException {
		return connect(dbConfig, "");
	}

	public static Connection connect(DbConfig dbConfig, String database) throws SQLException {

		String url = String.format("%s%s", dbConfig.getJdbcUrl(), database);
		String username = dbConfig.getAdmin().getUsername();
		String password = dbConfig.getAdmin().getPassword();

		log.info(String.format("Connecting to '%s' as '%s'", url, username));

		Properties connectionProps = new Properties();
		connectionProps.put("user", username);
		connectionProps.put("password", password);

		return DriverManager.getConnection(url, connectionProps);
	}

	public static boolean databaseExists(Connection con, String databaseName) throws SQLException {

		String sql = String.format("SELECT COUNT(*) FROM pg_catalog.pg_database WHERE datname = '%s'", databaseName);

		int count = 0;

		try (Statement stmt = con.createStatement()) {
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				count = rs.getInt(1);
			}
		} catch (SQLException e) {
			log.catching(e);
			throw e;
		}

		return count > 0;
	}

	public static boolean userExists(Connection con, String username) throws SQLException {

		String sql = String.format("SELECT 1 FROM pg_roles WHERE rolname='%s'", username);

		int count = 0;

		try (Statement stmt = con.createStatement()) {
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				count = rs.getInt(1);
			}
		} catch (SQLException e) {
			log.catching(e);
			throw e;
		}

		return count > 0;
	}

	public static void removePrivilagesFromUser(Connection con, String database, String username) throws Exception {
		log.debug(String.format("removePrivilagesFromUser: '%s'", username));

		String sql = String.format("REVOKE ALL PRIVILEGES ON DATABASE %s FROM %s;", database, username);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void reAssignUserRoles(Connection con, String username, String username2) throws Exception {
		log.debug(String.format("reAssignUserRoles: '%s'", username));

		String sql = String.format("REASSIGN OWNED BY %s TO %s;", username, username2);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void dropOwnedByUser(Connection con, String username) throws Exception {
		log.debug(String.format("dropOwnedByUser: '%s'", username));

		String sql = String.format("DROP OWNED BY %s;", username);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void deleteUser(Connection con, String username) throws Exception {
		log.debug(String.format("deleteUser: '%s'", username));

		String sql = String.format("DROP ROLE %s;", username);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void deleteDatabase(Connection con, String database) throws Exception {
		log.debug(String.format("deleteDatabase: '%s'", database));

		String sql = String.format("DROP DATABASE %s", database);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void deleteTable(Connection con, String table) throws Exception {
		log.debug(String.format("deleteTable: %s", table));

		String sql = String.format("DROP TABLE %s", table);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void createDatabase(Connection con, String database) throws Exception {
		log.debug(String.format("createDatabase: '%s'", database));

		String sql = String.format("CREATE DATABASE %s", database);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void createUser(Connection con, String username, String password) throws Exception {
		log.debug(String.format("createUser: '%s'", username));

		String sql = String.format("CREATE USER %s WITH ENCRYPTED PASSWORD '%s';", username, password);

		try (Statement stmt = con.createStatement()) {
			log.info(sql);
			stmt.executeUpdate(sql);
		} catch (Exception e) {
			throw e;
		}
	}

	public static void grantPrivilagesToUser(Connection con, String database, String username) throws Exception {
		log.debug(String.format("grantPrivilagesToUser: database: '%s', username: '%s'", database, username));

		List<String> list = new ArrayList<String>();
		list.add(String.format("GRANT ALL PRIVILEGES ON DATABASE %s TO %s;", database, username));
		list.add(String.format("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA %s TO %s;", "public", username));
		list.add(String.format("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %s TO %s;", "public", username));

		for (String sql : list) {
			try (Statement stmt = con.createStatement()) {
				log.info(sql);
				stmt.executeUpdate(sql);
			} catch (Exception e) {
				throw e;
			}
		}
	}
}
