package com.rsmaxwell.diaries.responder.buildinfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import com.rsmaxwell.mqtt.rpc.common.buildinfo.AbstractBuildInfo;

public class BuildInfo extends AbstractBuildInfo {

    private static final String RESOURCE_NAME = "/build-info.properties";

    public BuildInfo() {
        Properties properties = loadProperties();

        name = properties.getProperty(
                "name",
                "diaries-responder");

        version = properties.getProperty(
                "version",
                "unknown");

        buildID = properties.getProperty(
                "buildId",
                "(none)");

        builddate = properties.getProperty(
                "buildDate",
                "unknown");

        gitCommit = properties.getProperty(
                "gitCommit",
                "(none)");

        gitBranch = properties.getProperty(
                "gitBranch",
                "(none)");

        gitURL = properties.getProperty(
                "gitUrl",
                "(none)");
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();

        try (InputStream inputStream =
                BuildInfo.class.getResourceAsStream(RESOURCE_NAME)) {

            if (inputStream == null) {
                return properties;
            }

            properties.load(inputStream);
            return properties;

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read " + RESOURCE_NAME,
                    exception);
        }
    }

    public static String toStaticString() {
        return new BuildInfo().toString();
    }

    @Override
    public void printAll() {
        System.out.println(toStaticString());
    }
}
