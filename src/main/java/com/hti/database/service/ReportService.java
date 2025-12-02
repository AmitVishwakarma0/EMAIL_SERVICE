package com.hti.database.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.entity.ReportEntry;
import com.hti.util.GlobalVar;
import com.hti.util.Queue;

public class ReportService implements Runnable {

	private Logger logger = LoggerFactory.getLogger("dbLogger");
	private boolean stop;
	private Queue processQueue;
	private String systemId;
	private String table_name = null;

	public ReportService(String systemId, Queue processQueue) {
		logger.info(systemId + "_report thread starting");
		this.systemId = systemId;
		this.processQueue = processQueue;
		this.table_name = "report_" + systemId;
		new Thread(this, systemId + "_ReportInsert").start();
	}

	@Override
	public void run() {
		checkTable();
		while (!stop) {
			if (processQueue.isEmpty()) {
				try {
					Thread.sleep(GlobalVar.QUEUE_WAIT_TIME);
				} catch (InterruptedException e) {
				}
				continue;
			}
			logger.info("processQueue: " + processQueue.size());
			ReportEntry entry = null;
			try (Connection connection = GlobalVar.connectionPool.getConnection();
					PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table_name
							+ " (msg_id, batch_id, recipient, received_on, submit_on, status, status_code, remarks) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

				connection.setAutoCommit(false);
				int count = 0;

				while (!processQueue.isEmpty()) {
					entry = (ReportEntry) processQueue.dequeue();

					statement.setString(1, entry.getMsgId());
					statement.setString(2, entry.getBatchId());
					statement.setString(3, entry.getRecipient());
					statement.setTimestamp(4, entry.getReceivedOn());
					statement.setTimestamp(5, entry.getSubmitOn());
					statement.setString(6, entry.getStatus());
					statement.setInt(7, entry.getStatusCode());
					statement.setString(8, entry.getRemarks());
					statement.addBatch();
					if (++count > GlobalVar.JDBC_BATCH_SIZE) {
						break;
					}
				}

				if (count > 0) {
					int[] executed = statement.executeBatch();
					connection.commit();
					logger.info("Executed: " + executed.length);
				}

			} catch (SQLException e) {
				logger.error(systemId, e);
			} catch (Exception e) {
				logger.error(systemId, e);
			}

		}
		logger.info(systemId + "_ReportInsert Stopped.Queue:" + processQueue.size());
	}

	public void stop() {
		logger.info(systemId + "_ReportInsert Stopping.Queue:" + processQueue.size());
		stop = true;
	}
	
	private boolean checkTable() {
	    boolean tableExists = false;

	    try (Connection connection = GlobalVar.connectionPool.getConnection()) {

	        DatabaseMetaData meta = connection.getMetaData();
	        try (ResultSet rs = meta.getTables(null, null, table_name, null)) {
	            if (rs.next()) {
	                tableExists = true;
	            }
	        }

	        if (tableExists) {
	            logger.info(table_name + " already exists. No need to create.");
	            return true;
	        }

	        // If table does NOT exist → create table with partitions
	        String sql = buildCreateTableQuery();
	        logger.info(sql);

	        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
	            stmt.executeUpdate();
	            logger.info("<-- " + table_name + " created -->");
	            return true;
	        }

	    } catch (Exception e) {
	        logger.error(table_name + " check/create error: ", e);
	    }

	    return false;
	}


	private String buildCreateTableQuery() {
		StringBuilder sb = new StringBuilder();
		sb.append("CREATE TABLE IF NOT EXISTS ").append(table_name).append("(").append("msg_id bigint NOT NULL, \n")
				.append("batch_id bigint DEFAULT 0, \n").append("recipient varchar(50) DEFAULT NULL, \n")
				.append("status varchar(12) DEFAULT NULL, \n").append("status_code int(3) DEFAULT 0, \n")
				.append("received_on timestamp NULL DEFAULT CURRENT_TIMESTAMP, \n")
				.append("submit_on timestamp NULL DEFAULT CURRENT_TIMESTAMP, \n")
				.append("remarks varchar(100) DEFAULT NULL, \n")
				.append("partition_id INT GENERATED ALWAYS AS (CAST(LEFT(msg_id, 6) AS UNSIGNED)) STORED, \n")
				.append("PRIMARY KEY (msg_id, partition_id)")
				.append(")\nENGINE=InnoDB\nPARTITION BY RANGE (partition_id) (\n");

		// previous partitions
		for (int i = 2; i > 0; i--) {
			String partName = "p" + LocalDate.now().minusDays(i).format(DateTimeFormatter.ofPattern("yyMMdd"));
			String partValue = LocalDate.now().minusDays(i - 1).format(DateTimeFormatter.ofPattern("yyMMdd"));
			sb.append("PARTITION ").append(partName).append(" VALUES LESS THAN (").append(partValue).append("), \n");
		}

		// current partition
		String currentName = "p" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
		String currentValue = LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyMMdd"));
		sb.append("PARTITION ").append(currentName).append(" VALUES LESS THAN (").append(currentValue).append("), \n");

		// tomorrow partition
		String nextName = "p" + LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyMMdd"));
		String nextValue = LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("yyMMdd"));
		sb.append("PARTITION ").append(nextName).append(" VALUES LESS THAN (").append(nextValue).append("), \n");

		sb.append("PARTITION pmax VALUES LESS THAN MAXVALUE);");

		String sql = sb.toString();
		logger.info(sql);
		return sql;
	}

}
