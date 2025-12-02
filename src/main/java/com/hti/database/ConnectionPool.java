package com.hti.database;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

public class ConnectionPool {

	private DataSource dataSource;

	public ConnectionPool() throws Exception {
		this.dataSource = new DataSourceFactory().createDataSource();
	}

	public synchronized Connection getConnection() throws SQLException {
		return dataSource.getConnection();
	}

	public synchronized JdbcTemplate getJdbcConnection() throws SQLException {
		return new JdbcTemplate(dataSource);
	}
}
