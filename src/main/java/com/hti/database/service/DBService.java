package com.hti.database.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.entity.EmailEntry;
import com.hti.entity.ImapEntry;
import com.hti.entity.RecipientsEntry;
import com.hti.entity.ScheduleEntry;
import com.hti.entity.SmtpEntry;
import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.ScheduleFilterRequest;
import com.hti.util.EmailStatus;
import com.hti.util.GlobalVar;

public class DBService {

	private Logger logger = LoggerFactory.getLogger(DBService.class);

	public SmtpEntry getSmtpEntry(String systemId, int smtpId) {
		String sql = "SELECT * FROM smtp_config WHERE id = ? and system_id = ?";
		try (Connection con = GlobalVar.connectionPool.getConnection();
			PreparedStatement stmt = con.prepareStatement(sql)) {
			stmt.setInt(1, smtpId);
			stmt.setString(2, systemId);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				SmtpEntry entry = new SmtpEntry();
				entry.setId(rs.getInt("id"));
				entry.setHost(rs.getString("host"));
				entry.setPort(rs.getInt("port"));
				entry.setEmailUser(rs.getString("email_user"));
				entry.setEmailPassword(rs.getString("email_password"));
				entry.setVerified(rs.getBoolean("verified"));
				entry.setWebhookUrl(rs.getString("web_hook_url"));
				String enc = rs.getString("encryption_type");
				entry.setEncryptionType(enc != null ? SmtpEntry.EncryptionType.valueOf(enc.toUpperCase())
						: SmtpEntry.EncryptionType.NONE);

				return entry;
			}

		} catch (SQLException e) {
			logger.error("Error fetching SMTP entry for id {}", smtpId, e);
		}

		return null;
	}

	public boolean createBatchEntry(EmailEntry entry) {
		String tableName = "batch_" + entry.getSystemId();

		StringBuilder sb = new StringBuilder();
		sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append("batch_id BIGINT PRIMARY KEY, ")
				.append("system_id VARCHAR(50), ").append("smtp_id INT, ").append("ip_address VARCHAR(50), ")
				.append("subject TEXT, ").append("body TEXT, ").append("cc_recipients TEXT, ")
				.append("bcc_recipients TEXT, ").append("attachments TEXT, ").append("total_recipients INT, ")
				.append("delay DOUBLE(10,5) NOT NULL DEFAULT 0, ").append("created_on TIMESTAMP, ")
				.append("updated_on TIMESTAMP NULL, ").append("status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE', ")
				.append("type VARCHAR(12) NOT NULL DEFAULT 'IMMEDIATE'").append(") ENGINE=MyISAM");
		System.out.println("CreateSQL: " + sb.toString());

		String insertSQL = "INSERT INTO " + tableName + " (batch_id, system_id, smtp_id, ip_address, subject, body,"
				+ " cc_recipients, bcc_recipients, attachments, total_recipients, delay,"
				+ " created_on, updated_on, status,type)" + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?,?)";
		// updated_on always NULL
		System.out.println("insertSQL: " + insertSQL);

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement createStmt = con.prepareStatement(sb.toString());
				PreparedStatement insertStmt = con.prepareStatement(insertSQL)) {

			if (createStmt.executeUpdate() > 0) {
				logger.info(tableName + " Created.");
			} else {
				logger.info(tableName + " Not Created.");
			}

			insertStmt.setString(1, entry.getBatchId());
			insertStmt.setString(2, entry.getSystemId());
			insertStmt.setInt(3, entry.getSmtpId());
			insertStmt.setString(4, entry.getIpAddress());
			insertStmt.setString(5, entry.getSubject());
			insertStmt.setString(6, entry.getBody());
			insertStmt.setString(7, entry.getCcRecipients());
			insertStmt.setString(8, entry.getBccRecipients());
			insertStmt.setString(9, entry.getAttachments());
			insertStmt.setInt(10, entry.getTotalRecipients());
			insertStmt.setDouble(11, entry.getDelay());
			insertStmt.setTimestamp(12, entry.getCreatedOn());
			insertStmt.setString(13, entry.getBatchStatus().name());
			insertStmt.setString(14, entry.getBatchType().name());
			return insertStmt.executeUpdate() > 0;

		} catch (Exception e) {
			logger.error("Error creating batch entry for system {}: {}", entry.getSystemId(), e.getMessage(), e);
		}
		return false;
	}

	public boolean saveRecipientsEntry(List<RecipientsEntry> list, String systemId, String batchId) {
		if (list == null || list.isEmpty()) {
			return false;
		}

		String tableName = "recipient_" + systemId.toLowerCase() + "_" + batchId;

		String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + "msg_id BIGINT PRIMARY KEY, "
				+ "recipient VARCHAR(100) NOT NULL, " + "flag CHAR(1) DEFAULT 'F')";

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement createStmt = con.prepareStatement(createTableSQL)) {

			createStmt.executeUpdate(); // Create table once

		} catch (SQLException e) {
			logger.error("Table creation failed for batch {}: {}", batchId, e.getMessage(), e);
			return false;
		}

		// Thread pool for inserting entries
		ExecutorService executor = Executors.newFixedThreadPool(10); // adjust thread count

		// Prepare tasks
		List<CompletableFuture<Boolean>> tasks = list.stream()
				.map(entry -> CompletableFuture.supplyAsync(() -> insertRecipient(entry, tableName), executor))
				.toList();

		// Wait for ALL threads to finish
		CompletableFuture<Void> allDone = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));

		try {
			allDone.get(); // Blocking wait here
		} catch (Exception e) {
			executor.shutdown();
			logger.error("Insertion error: {}", e.getMessage(), e);
			return false;
		}

		executor.shutdown();

		// Check if any task failed
		boolean allSuccess = tasks.stream().allMatch(t -> t.join());

		return allSuccess;
	}

	private boolean insertRecipient(RecipientsEntry entry, String tableName) {
		String insertSQL = "INSERT INTO " + tableName + " (msg_id, recipient) VALUES (?, ?)";

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = con.prepareStatement(insertSQL)) {

			stmt.setString(1, entry.getMsgId());
			stmt.setString(2, entry.getRecipient());
			stmt.executeUpdate();
			return true;

		} catch (SQLException e) {
			logger.error("Insert failed for msg {}: {}", entry.getMsgId(), e.getMessage());
			return false;
		}
	}

	public List<RecipientsEntry> listPendingRecipients(String systemId, String batchId) {
		String table_name = "recipient_" + systemId + "_" + batchId;
		logger.info(table_name + " listing pendings");
		String sql = "SELECT msg_id,recipient FROM " + table_name + " where flag='F'";
		List<RecipientsEntry> list = new ArrayList<>();

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				list.add(new RecipientsEntry(rs.getString("msg_id"), rs.getString("recipient")));
			}
			logger.info("{} Pendings: {}", table_name, list.size());

		} catch (SQLException e) {
			logger.error("SQL error in {} pending list", table_name, e);
		} catch (Exception e) {
			logger.error("Unexpected error in {} pending list", table_name, e);
		}

		return list;
	}

	public int countPendingRecipients(String systemId, String batchId) {
		int returnCounter = 0;
		String table_name = "recipient_" + systemId + "_" + batchId;
		logger.info(table_name + " listing pendings");
		String sql = "SELECT count(msg_id) as pending FROM " + table_name + " where flag='F'";
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rs = statement.executeQuery()) {
			if (rs.next()) {
				returnCounter = rs.getInt("pending");
			}
			logger.info("{} Pendings: {}", table_name, returnCounter);

		} catch (SQLException e) {
			logger.error("SQL error in {} pending list", table_name, e);
		} catch (Exception e) {
			logger.error("Unexpected error in {} pending list", table_name, e);
		}

		return returnCounter;
	}

	public void updateBatchStatus(String systemId, String batchId, String status) {
		String table = "batch_" + systemId;

		String sql = "UPDATE " + table + " SET status = ?, updated_on = NOW() WHERE batch_id = ?";

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {

			stmt.setString(1, status);
			stmt.setString(2, batchId);

			stmt.executeUpdate();

		} catch (SQLException e) {
			logger.error("Error updating batch status for batch {}", batchId, e);
		}
	}

	public void updateBatch(EmailEntry entry) {
		String table = "batch_" + entry.getSystemId();

		String sql = "UPDATE " + table + " SET " + "smtp_id = ?, subject = ?, body = ?, "
				+ "cc_recipients = ?, bcc_recipients = ?, " + "status = ?, delay = ?,updated_on = NOW() "
				+ "WHERE batch_id = ?";

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, entry.getSmtpId());
			stmt.setString(2, entry.getSubject());
			stmt.setString(3, entry.getBody());
			stmt.setString(4, entry.getCcRecipients());
			stmt.setString(5, entry.getBccRecipients());
			stmt.setString(6, entry.getBatchStatus().name());
			stmt.setDouble(7, entry.getDelay());
			stmt.setString(8, entry.getBatchId());
			stmt.executeUpdate();

		} catch (SQLException e) {
			logger.error("Error updating batch {}", entry.getBatchId(), e);
		}
	}

	public EmailEntry getEntry(String systemId, String batchId) {
		String table = "batch_" + systemId;

		String sql = "SELECT * FROM " + table + " WHERE batch_id = ?";

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, batchId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return new EmailEntry(rs.getString("batch_id"), rs.getString("system_id"),
							rs.getString("ip_address"), rs.getInt("smtp_id"), rs.getString("subject"),
							rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
							rs.getString("attachments"), rs.getDouble("delay"), rs.getInt("total_recipients"),
							rs.getTimestamp("created_on"), EmailEntry.BatchStatus.valueOf(rs.getString("status")),
							EmailEntry.BatchType.valueOf(rs.getString("type")));
				}
			}
		} catch (SQLException e) {
			logger.error("Error loading batch {}", batchId, e);
		}

		return null;
	}

	public List<EmailEntry> listPendingEntries() {
		String sql = "SHOW TABLES LIKE 'batch_%'";
		logger.info(" SQL: " + sql.toString());
		Set<String> tables = new HashSet<String>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql.toString());
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				tables.add(rs.getString(1));
			}
			logger.info("Tables: {}", tables.size());
		} catch (SQLException e) {
			logger.error("SQL error in {} Tables list", e);
		} catch (Exception e) {
			logger.error("Unexpected error in {} Tables list", e);
		}
		List<EmailEntry> list = new ArrayList<>();

		String query = "SELECT * FROM %s WHERE status = 'ACTIVE'";

		try (Connection connection = GlobalVar.connectionPool.getConnection()) {
			for (String table : tables) {
				String finalQuery = String.format(query, table);
				try (PreparedStatement statement = connection.prepareStatement(finalQuery);
						ResultSet rs = statement.executeQuery()) {

					while (rs.next()) {
						list.add(new EmailEntry(rs.getString("batch_id"), rs.getString("system_id"),
								rs.getString("ip_address"), rs.getInt("smtp_id"), rs.getString("subject"),
								rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
								rs.getString("attachments"), rs.getDouble("delay"), rs.getInt("total_recipients"),
								rs.getTimestamp("created_on"), EmailEntry.BatchStatus.valueOf(rs.getString("status")),
								EmailEntry.BatchType.valueOf(rs.getString("type"))));
					}

				} catch (SQLException e) {
					logger.error("Error reading table {}", table, e);
				}
			}

			logger.info("ACTIVE entries collected: {}", list.size());

		} catch (SQLException e) {
			logger.error("SQL error when creating DB connection", e);
		}
		return list;
	}

	public List<EmailEntry> listEntries(String systemId, BatchProcessFilterRequest batchProcessFilterRequest) {
		StringBuilder sql = new StringBuilder("SELECT * FROM batch_" + systemId);
		if (batchProcessFilterRequest != null) {
			List<String> conditions = new ArrayList<>();
			if (batchProcessFilterRequest.getBatchId() > 0) {
				conditions.add("batch_id = " + batchProcessFilterRequest.getBatchId());
			} else {
				if (batchProcessFilterRequest.getStatus() != null) {
					Set<String> statusSet = new HashSet<>();
					JSONArray arr = new JSONArray(batchProcessFilterRequest.getStatus());
					for (int i = 0; i < arr.length(); i++) {
						statusSet.add(arr.getString(i).trim());
					}
					if (!statusSet.isEmpty()) {
						String inClause = statusSet.stream().map(s -> "'" + s + "'").collect(Collectors.joining(","));
						conditions.add("status IN (" + inClause + ")");
					}
				}
				if (batchProcessFilterRequest.getSmtpId() > 0) {
					conditions.add("smtp_id = " + batchProcessFilterRequest.getSmtpId());
				}
				if (batchProcessFilterRequest.getStartTime() != null
						&& batchProcessFilterRequest.getEndTime() != null) {
					conditions.add("created_on BETWEEN '" + batchProcessFilterRequest.getStartTime() + "' AND '"
							+ batchProcessFilterRequest.getEndTime() + "'");
				}
			}
			if (!conditions.isEmpty()) {
				sql.append(" WHERE ").append(String.join(" AND ", conditions));
			}

		}
		logger.info(systemId + " SQL: " + sql.toString());
		List<EmailEntry> list = new ArrayList<EmailEntry>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql.toString());
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				list.add(new EmailEntry(rs.getString("batch_id"), rs.getString("system_id"), rs.getString("ip_address"),
						rs.getInt("smtp_id"), rs.getString("subject"), rs.getString("body"),
						rs.getString("cc_recipients"), rs.getString("bcc_recipients"), rs.getString("attachments"),
						rs.getDouble("delay"), rs.getInt("total_recipients"), rs.getTimestamp("created_on"),
						EmailEntry.BatchStatus.valueOf(rs.getString("status")),
						EmailEntry.BatchType.valueOf(rs.getString("type"))));
			}
			logger.info("{} Batches: {}", systemId, list.size());
		} catch (SQLException e) {
			logger.error("SQL error in {} Batches list", systemId, e);
		} catch (Exception e) {
			logger.error("Unexpected error in {} Batches list", systemId, e);
		}
		return list;
	}

	public void updateSmtpStatus(int smtpId) {
		String sql = "UPDATE smtp_config SET verified=? where id = ?";
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setBoolean(1, true);
			stmt.setInt(2, smtpId);
			stmt.executeUpdate();
			logger.info(smtpId + " smtp config updated as verified");
		} catch (SQLException e) {
			logger.error("Error updating smtp_config {}", smtpId, e);
		}
	}

	public void addUserReportPartition() {
		String sql = "SHOW TABLES LIKE 'report_%'";
		logger.info("SQL: {}", sql);

		Set<String> tables = new HashSet<>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rs = statement.executeQuery()) {

			while (rs.next()) {
				tables.add(rs.getString(1));
			}
			logger.info("Tables found: {}", tables.size());
		} catch (SQLException e) {
			logger.error("SQL error in Tables list", e);
			return;
		}

		// Tomorrow partition parameters
		String nextPartitionName = "p" + LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyMMdd"));

		String nextPartitionValue = LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("yyMMdd"));

		try (Connection connection = GlobalVar.connectionPool.getConnection()) {
			for (String table : tables) {

				// 1) Check if the partition already exists
				String checkSql = "SELECT PARTITION_NAME FROM INFORMATION_SCHEMA.PARTITIONS "
						+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME = ?";
				try (PreparedStatement ps = connection.prepareStatement(checkSql)) {
					ps.setString(1, table);
					ps.setString(2, nextPartitionName);

					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							logger.info("Partition {} already exists on {}", nextPartitionName, table);
							continue; // skip
						}
					}
				}

				// 2) Drop pmax partition (needed before adding new partition)
				String dropMax = "ALTER TABLE " + table + " DROP PARTITION pmax";
				logger.info("Dropping pmax on {}", table);

				try (PreparedStatement ps = connection.prepareStatement(dropMax)) {
					ps.execute();
				} catch (SQLException e) {
					logger.warn("pmax drop failed on {} (maybe already dropped)", table);
				}

				// 3) Add new next-day partition
				String addPartitionSql = "ALTER TABLE " + table + " ADD PARTITION (PARTITION " + nextPartitionName
						+ " VALUES LESS THAN (" + nextPartitionValue + "))";

				logger.info("Adding partition {} to {}", nextPartitionName, table);
				try (PreparedStatement ps = connection.prepareStatement(addPartitionSql)) {
					ps.execute();
				}

				// 4) Re-add pmax partition
				String addMaxSql = "ALTER TABLE " + table
						+ " ADD PARTITION (PARTITION pmax VALUES LESS THAN (MAXVALUE))";

				logger.info("Re-adding pmax on {}", table);
				try (PreparedStatement ps = connection.prepareStatement(addMaxSql)) {
					ps.execute();
				}

				logger.info("Partition {} added successfully to {}", nextPartitionName, table);
			}
		} catch (SQLException e) {
			logger.error("SQL error when adding partition", e);
		}
	}

	public boolean createScheduleEntry(ScheduleEntry entry) {
		String tableName = "schedule_" + entry.getSystemId();

		StringBuilder sb = new StringBuilder();
		sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append("batch_id BIGINT PRIMARY KEY, ")
				.append("system_id VARCHAR(15), ").append("smtp_id INT, ").append("ip_address VARCHAR(50), ")
				.append("subject TEXT, ").append("body TEXT, ").append("cc_recipients TEXT, ")
				.append("bcc_recipients TEXT, ").append("attachments TEXT, ").append("total_recipients INT, ")
				.append("delay DOUBLE(10,5) NOT NULL DEFAULT 0, ").append("created_on TIMESTAMP, ")
				.append("updated_on TIMESTAMP NULL, ").append("gmt VARCHAR(10), ").append("schedule_on TIMESTAMP, ")
				.append("server_time TIMESTAMP,").append("status VARCHAR(12) NOT NULL DEFAULT 'PENDING'")
				.append(") ENGINE=MyISAM");

		String insertSQL = "INSERT INTO " + tableName + " (batch_id, system_id, smtp_id, ip_address, subject, body,"
				+ " cc_recipients, bcc_recipients, attachments, total_recipients, delay,"
				+ " created_on, gmt,schedule_on,server_time)" + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?)";
		// updated_on always NULL

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement createStmt = con.prepareStatement(sb.toString());
				PreparedStatement insertStmt = con.prepareStatement(insertSQL)) {

			createStmt.executeUpdate();

			insertStmt.setString(1, entry.getBatchId());
			insertStmt.setString(2, entry.getSystemId());
			insertStmt.setInt(3, entry.getSmtpId());
			insertStmt.setString(4, entry.getIpAddress());
			insertStmt.setString(5, entry.getSubject());
			insertStmt.setString(6, entry.getBody());
			insertStmt.setString(7, entry.getCcRecipients());
			insertStmt.setString(8, entry.getBccRecipients());
			insertStmt.setString(9, entry.getAttachments());
			insertStmt.setInt(10, entry.getTotalRecipients());
			insertStmt.setDouble(11, entry.getDelay());
			insertStmt.setTimestamp(12, entry.getCreatedOn());
			insertStmt.setString(13, entry.getGmt());
			insertStmt.setTimestamp(14, Timestamp.valueOf(entry.getScheduledOn()));
			insertStmt.setTimestamp(15, Timestamp.valueOf(entry.getServerTime()));
			return insertStmt.executeUpdate() > 0;

		} catch (SQLException e) {
			logger.error("Error creating Schedule entry for systemId {}: {}", entry.getSystemId(), e.getMessage(), e);
		}
		return false;
	}

	public boolean saveScheduledRecipients(List<String> list, String systemId, String batchId) {
		if (list == null || list.isEmpty()) {
			return false;
		}
		String tableName = "sch_recipient_" + systemId.toLowerCase() + "_" + batchId;
		String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
				+ "recipient VARCHAR(50) NOT NULL PRIMARY KEY" + ") ENGINE=MyISAM";

		// Create table only once
		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement createStmt = con.prepareStatement(createTableSQL)) {
			createStmt.executeUpdate();
		} catch (SQLException e) {
			logger.error("Table creation failed for Schedule {}: {}", batchId, e.getMessage(), e);
			return false;
		}

		ExecutorService executor = Executors.newFixedThreadPool(10);

		List<CompletableFuture<Boolean>> tasks = list.stream()
				.map(entry -> CompletableFuture.supplyAsync(() -> insertScheduleRecipient(entry, tableName), executor))
				.toList();

		try {
			CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
		} catch (Exception e) {
			logger.error("Insertion error: {}", e.getMessage(), e);
			executor.shutdown();
			return false;
		}

		executor.shutdown();

		// Final success check
		return tasks.stream().allMatch(CompletableFuture::join);
	}

	private boolean insertScheduleRecipient(String recipient, String tableName) {
		String insertSQL = "INSERT IGNORE INTO " + tableName + " (recipient) VALUES (?)";
		// INSERT IGNORE prevents duplicate key failure

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = con.prepareStatement(insertSQL)) {
			stmt.setString(1, recipient);
			stmt.executeUpdate();
			return true;

		} catch (SQLException e) {
			logger.error("Insert failed for recipient {}: {}", recipient, e.getMessage());
			return false;
		}
	}

	public List<ScheduleEntry> loadTodaySchedules() {
		String sql = "SHOW TABLES LIKE 'schedule_%'";
		logger.info(" SQL: " + sql.toString());
		Set<String> tables = new HashSet<String>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql.toString());
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				tables.add(rs.getString(1));
			}
			logger.info("Tables: {}", tables.size());
		} catch (SQLException e) {
			logger.error("SQL error in {} Tables list", e);
		} catch (Exception e) {
			logger.error("Unexpected error in {} Tables list", e);
		}
		List<ScheduleEntry> list = new ArrayList<>();

		String query = "SELECT * FROM %s WHERE DATE(server_time) = '"
				+ new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + "' and status = 'PENDING'";

		try (Connection connection = GlobalVar.connectionPool.getConnection()) {
			for (String table : tables) {
				String finalQuery = String.format(query, table);
				logger.info(finalQuery);
				try (PreparedStatement statement = connection.prepareStatement(finalQuery);
						ResultSet rs = statement.executeQuery()) {
					while (rs.next()) {
						list.add(new ScheduleEntry(rs.getString("batch_id"), rs.getString("system_id"),
								rs.getString("ip_address"), rs.getInt("smtp_id"), rs.getString("subject"),
								rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
								rs.getString("attachments"), rs.getDouble("delay"), rs.getInt("total_recipients"),
								rs.getTimestamp("created_on"), rs.getTimestamp("server_time").toLocalDateTime(),
								rs.getString("gmt"), rs.getTimestamp("schedule_on").toLocalDateTime(),
								EmailEntry.BatchStatus.valueOf(rs.getString("status"))));
					}

				} catch (SQLException e) {
					logger.error("Error reading table {}", table, e);
				}
			}

			logger.info("Today Scheduled entries collected: {}", list.size());

		} catch (SQLException e) {
			logger.error("SQL error when creating DB connection", e);
		}
		return list;
	}

	public ScheduleEntry getScheduleEntry(String systemId, String batchId) {
		ScheduleEntry entry = null;
		String query = "SELECT * FROM schedule_" + systemId + " WHERE batch_id = ?";
		logger.info("Executing: {}", query);

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setString(1, batchId);
			try (ResultSet rs = statement.executeQuery()) {
				if (rs.next()) {
					entry = new ScheduleEntry(rs.getString("batch_id"), rs.getString("system_id"),
							rs.getString("ip_address"), rs.getInt("smtp_id"), rs.getString("subject"),
							rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
							rs.getString("attachments"), rs.getDouble("delay"), rs.getInt("total_recipients"),
							rs.getTimestamp("created_on"), rs.getTimestamp("server_time").toLocalDateTime(),
							rs.getString("gmt"), rs.getTimestamp("schedule_on").toLocalDateTime(),
							EmailEntry.BatchStatus.valueOf(rs.getString("status")));
				}
			}
			logger.info("Scheduled entry collected: {}", entry);
		} catch (SQLException e) {
			logger.error("SQL error while fetching schedule entry", e);
		}

		return entry;
	}

	public boolean abortSchedule(String systemId, String batchId) {
		String table = "schedule_" + systemId;
		String sql = "update " + table + " set status = ? WHERE batch_id = ?";
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, "ABORTED");
			stmt.setString(2, batchId);
			int rows = stmt.executeUpdate();
			if (rows > 0) {
				logger.info("Schedule entry aborted from DB for batch {}", batchId);
				return true;
			}
		} catch (SQLException e) {
			logger.error("Error aborting schedule for batch {}", batchId, e);
		}

		return false;
	}

	public boolean updateScheduleEntry(ScheduleEntry entry) {
		String table = "schedule_" + entry.getSystemId();

		String sql = "UPDATE " + table + " SET " + "smtp_id = ?, subject = ?, body = ?, "
				+ "cc_recipients = ?, bcc_recipients = ?, "
				+ " delay = ?,updated_on = NOW(), gmt=?, schedule_on=?, server_time=? " + "WHERE batch_id = ?";

		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, entry.getSmtpId());
			stmt.setString(2, entry.getSubject());
			stmt.setString(3, entry.getBody());
			stmt.setString(4, entry.getCcRecipients());
			stmt.setString(5, entry.getBccRecipients());
			stmt.setDouble(6, entry.getDelay());
			stmt.setString(7, entry.getGmt());
			stmt.setTimestamp(8, Timestamp.valueOf(entry.getScheduledOn()));
			stmt.setTimestamp(9, Timestamp.valueOf(entry.getServerTime()));
			stmt.setString(10, entry.getBatchId());
			int rows = stmt.executeUpdate();
			if (rows > 0) {
				logger.info("Schedule entry Updated for batch {}", entry.getBatchId());
				return true;
			}
		} catch (SQLException e) {
			logger.error("Error updating Scheduled Batch {}", entry.getBatchId(), e);
		}
		return false;
	}

	public List<ScheduleEntry> listSchedules(String systemId, ScheduleFilterRequest batchProcessFilterRequest) {
		StringBuilder sql = new StringBuilder("SELECT * FROM schedule_" + systemId);
		if (batchProcessFilterRequest != null) {
			List<String> conditions = new ArrayList<>();
			if (batchProcessFilterRequest.getBatchId() > 0) {
				conditions.add("batch_id = " + batchProcessFilterRequest.getBatchId());
			} else {
				if (batchProcessFilterRequest.getSmtpId() > 0) {
					conditions.add("smtp_id = " + batchProcessFilterRequest.getSmtpId());
				}
				if (batchProcessFilterRequest.getStartTime() != null
						&& batchProcessFilterRequest.getEndTime() != null) {
					conditions.add("schedule_on BETWEEN '" + batchProcessFilterRequest.getStartTime() + "' AND '"
							+ batchProcessFilterRequest.getEndTime() + "'");
				}
			}
			if (!conditions.isEmpty()) {
				sql.append(" WHERE ").append(String.join(" AND ", conditions));
			}

		}
		logger.info(systemId + " SQL: " + sql.toString());
		List<ScheduleEntry> list = new ArrayList<ScheduleEntry>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql.toString())) {
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					list.add(new ScheduleEntry(rs.getString("batch_id"), rs.getString("system_id"),
							rs.getString("ip_address"), rs.getInt("smtp_id"), rs.getString("subject"),
							rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
							rs.getString("attachments"), rs.getDouble("delay"), rs.getInt("total_recipients"),
							rs.getTimestamp("created_on"), rs.getTimestamp("server_time").toLocalDateTime(),
							rs.getString("gmt"), rs.getTimestamp("schedule_on").toLocalDateTime(),
							EmailEntry.BatchStatus.valueOf(rs.getString("status"))));
				}
			}
			logger.info("Scheduled entries collected: {}", list.size());
		} catch (SQLException e) {
			logger.error("SQL error while fetching schedule entry", e);
		}
		return list;
	}

	public List<String> listScheduledRecipients(String systemId, String batchId) {
		String tableName = "sch_recipient_" + systemId.toLowerCase() + "_" + batchId;
		List<String> list = new ArrayList<String>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement("select recipient from " + tableName)) {
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					list.add(rs.getString("recipient"));
				}
			}
			logger.info(tableName + " Scheduled Recipients collected: {}", list.size());
		} catch (SQLException e) {
			logger.error(tableName + " SQL error while fetching Scheduled Recipients", e);
		}
		return list;
	}

	public void clearScheduleEntry(String systemId, String batchId) {
		String scheduleTable = "schedule_" + systemId;
		String recipientTable = "sch_recipient_" + systemId.toLowerCase() + "_" + batchId;

		// 1. Mark schedule entry as finished (recommended) OR delete it
		String updateSql = "UPDATE " + scheduleTable + " SET status = 'FINISHED' WHERE batch_id = ?";

		// 2. Drop recipient table if exists
		String dropSql = "DROP TABLE IF EXISTS " + recipientTable;

		try (Connection connection = GlobalVar.connectionPool.getConnection()) {

			// ---------- Update Schedule Table ----------
			try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
				ps.setString(1, batchId);
				int rows = ps.executeUpdate();
				logger.info("Updated schedule entry, affected rows: {}", rows);
			} catch (SQLException e) {
				logger.error("Error updating schedule entry for table {}", scheduleTable, e);
			}

			// ---------- Drop Recipient Table ----------
			try (PreparedStatement ps = connection.prepareStatement(dropSql)) {
				ps.executeUpdate();
				logger.info("Dropped recipient table: {}", recipientTable);
			} catch (SQLException e) {
				logger.error("Error dropping recipient table {}", recipientTable, e);
			}

		} catch (SQLException e) {
			logger.error("SQL error creating DB connection", e);
		}
	}

	public List<ImapEntry> listImapEntries() {
		List<ImapEntry> list = new ArrayList<ImapEntry>();
		String sql = "SELECT * FROM smtp_config WHERE read_inbox = ? and verified = ? and imap_host IS NOT NULL and imap_port > 0";
		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement stmt = con.prepareStatement(sql)) {
			stmt.setBoolean(1, true);
			stmt.setBoolean(2, true);
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				String enc = rs.getString("imap_enc_type");
				list.add(new ImapEntry(rs.getInt("id"), rs.getString("system_id"), rs.getString("imap_host"),
						rs.getInt("imap_port"), rs.getString("email_user"), rs.getString("email_password"),
						enc != null ? SmtpEntry.EncryptionType.valueOf(enc.toUpperCase())
								: SmtpEntry.EncryptionType.NONE));
			}
		} catch (SQLException e) {
			logger.error("Error fetching SMTP entries", e);
		}

		return list;
	}

	public void addUserInboxPartition() {
		String sql = "SHOW TABLES LIKE 'inbox_%'";
		logger.info("SQL: {}", sql);

		Set<String> tables = new HashSet<>();
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rs = statement.executeQuery()) {

			while (rs.next()) {
				tables.add(rs.getString(1));
			}
			logger.info("Tables found: {}", tables.size());
		} catch (SQLException e) {
			logger.error("SQL error in Tables list", e);
			return;
		}

		// Tomorrow partition parameters
		String nextPartitionName = "p" + LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyMMdd"));

		String nextPartitionValue = LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("yyMMdd"));

		try (Connection connection = GlobalVar.connectionPool.getConnection()) {
			for (String table : tables) {

				// 1) Check if the partition already exists
				String checkSql = "SELECT PARTITION_NAME FROM INFORMATION_SCHEMA.PARTITIONS "
						+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME = ?";
				try (PreparedStatement ps = connection.prepareStatement(checkSql)) {
					ps.setString(1, table);
					ps.setString(2, nextPartitionName);

					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							logger.info("Partition {} already exists on {}", nextPartitionName, table);
							continue; // skip
						}
					}
				}

				// 2) Drop pmax partition (needed before adding new partition)
				String dropMax = "ALTER TABLE " + table + " DROP PARTITION pmax";
				logger.info("Dropping pmax on {}", table);

				try (PreparedStatement ps = connection.prepareStatement(dropMax)) {
					ps.execute();
				} catch (SQLException e) {
					logger.warn("pmax drop failed on {} (maybe already dropped)", table);
				}

				// 3) Add new next-day partition
				String addPartitionSql = "ALTER TABLE " + table + " ADD PARTITION (PARTITION " + nextPartitionName
						+ " VALUES LESS THAN (" + nextPartitionValue + "))";

				logger.info("Adding partition {} to {}", nextPartitionName, table);
				try (PreparedStatement ps = connection.prepareStatement(addPartitionSql)) {
					ps.execute();
				}

				// 4) Re-add pmax partition
				String addMaxSql = "ALTER TABLE " + table
						+ " ADD PARTITION (PARTITION pmax VALUES LESS THAN (MAXVALUE))";

				logger.info("Re-adding pmax on {}", table);
				try (PreparedStatement ps = connection.prepareStatement(addMaxSql)) {
					ps.execute();
				}

				logger.info("Partition {} added successfully to {}", nextPartitionName, table);
			}
		} catch (SQLException e) {
			logger.error("SQL error when adding partition", e);
		}
	}

}
