package com.hti.database.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.entity.ReportEntry;
import com.hti.service.SingletonService;
import com.hti.util.GlobalVar;
import com.hti.util.Queue;

import lombok.AllArgsConstructor;
import lombok.Data;

public class InboxService implements Runnable {

	private Logger logger = LoggerFactory.getLogger(InboxService.class);
	private boolean stop;
	private Queue processQueue;
	private String systemId;
	private String table_name = null;
	private long lastActiveTime;
	private static final long IDLE_TIMEOUT = 600_000; // 10 minutes

	public InboxService(String systemId) {
		logger.info(systemId + "_InboxInsert thread starting");
		this.systemId = systemId;
		this.processQueue = new Queue();
		this.lastActiveTime = System.currentTimeMillis(); // reset idle timer
		this.table_name = "inbox_" + systemId;
		new Thread(this, systemId + "_InboxInsert").start();
	}

	public void submit(ReportEntry entry) {
		processQueue.enqueue(entry);
	}

	@Override
	public void run() {
		checkTable();
		while (!stop) {
			if (processQueue.isEmpty()) {
				long idleFor = System.currentTimeMillis() - lastActiveTime;

				if (idleFor > IDLE_TIMEOUT) {
					logger.info(systemId + "_InboxInsert Idle timeout. Auto-stopping.");
					SingletonService.removeUserInboxService(systemId); // remove from cache
					break;
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				continue;
			}
			lastActiveTime = System.currentTimeMillis();
			logger.info("processQueue: " + processQueue.size());
			InboxEntry entry = null;
			try (Connection connection = GlobalVar.connectionPool.getConnection();
					PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO " + table_name
							+ " (msg_id, smtp_id, email_user, from_email, subject, body, attachments, received_on) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

				connection.setAutoCommit(false);
				int count = 0;

				while (!processQueue.isEmpty()) {
					entry = (InboxEntry) processQueue.dequeue();

					statement.setString(1, entry.getMessageId());
					statement.setInt(2, entry.getSmtpId());
					statement.setString(3, entry.getEmailUser());
					statement.setString(4, entry.getFrom());
					statement.setString(5, entry.getSubject());
					statement.setString(6, entry.getBody());
					statement.setString(7, entry.getAttachments());
					statement.setTimestamp(8, entry.getReceivedOn());
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
		logger.info(systemId + "_InboxInsert Stopped.Queue:" + processQueue.size());
	}

	public void stop() {
		logger.info(systemId + "_InboxInsert Stopping.Queue:" + processQueue.size());
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
		sb.append("CREATE TABLE IF NOT EXISTS ").append(table_name).append("(").append("id BIGINT AUTO_INCREMENT, \n")
				.append("msg_id VARCHAR(100) NOT NULL, \n").append("smtp_id INT DEFAULT 0, \n")
				.append("email_user VARCHAR(50) DEFAULT NULL, \n").append("from_email VARCHAR(50) DEFAULT NULL, \n")
				.append("subject TEXT, \n").append("body TEXT, \n").append("attachments TEXT, \n")
				.append("received_on TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP, \n")
				.append("partition_id INT GENERATED ALWAYS AS (CAST(DATE_FORMAT(received_on, '%y%m%d') AS UNSIGNED)) STORED, \n")
				.append("PRIMARY KEY (id, partition_id), \n").append("UNIQUE KEY uk_msg_id (msg_id, partition_id)\n")
				.append(") ENGINE=InnoDB\nPARTITION BY RANGE (partition_id) (\n");

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

	public void insertEmail(int smtpId, String emailUser, String messageId, String from, String subject, String body,
			Timestamp timestamp, String jsonFileNames) {
		processQueue
				.enqueue(new InboxEntry(smtpId, emailUser, messageId, from, subject, body, timestamp, jsonFileNames));

	}

	@Data
	@AllArgsConstructor
	private class InboxEntry {
		private int smtpId;
		private String emailUser;
		private String messageId;
		private String from;
		private String subject;
		private String body;
		private Timestamp receivedOn;
		String attachments;
	}

}
