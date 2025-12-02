package com.hti.database;

import java.io.IOException;
import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DataSourceFactory {

	public DataSource createDataSource() throws IOException {
		Properties properties = new Properties();

		// Load the properties from the application.properties file
		properties.load(getClass().getClassLoader().getResourceAsStream("application.properties"));

		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(properties.getProperty("spring.datasource.url"));
		config.setUsername(properties.getProperty("spring.datasource.username"));
		config.setPassword(properties.getProperty("spring.datasource.password"));
		config.setDriverClassName(properties.getProperty("spring.datasource.driver-class-name"));

		// HikariCP specific settings
		config.setMaximumPoolSize(
				Integer.parseInt(properties.getProperty("spring.datasource.hikari.maximum-pool-size")));
		config.setMinimumIdle(Integer.parseInt(properties.getProperty("spring.datasource.hikari.minimum-idle")));
		config.setIdleTimeout(Long.parseLong(properties.getProperty("spring.datasource.hikari.idle-timeout")));
		config.setConnectionTimeout(
				Long.parseLong(properties.getProperty("spring.datasource.hikari.connection-timeout")));
		config.setMaxLifetime(Long.parseLong(properties.getProperty("spring.datasource.hikari.max-lifetime")));

		return new HikariDataSource(config);
	}

}
