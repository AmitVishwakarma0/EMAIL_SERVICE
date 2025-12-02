package com.hti.database.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.entity.EmailEntry;
import com.hti.entity.RecipientsEntry;
import com.hti.entity.SmtpEntry;
import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.util.GlobalVar;

public class DBService {

	private Logger logger = LoggerFactory.getLogger("dbLogger");

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

		String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + "batch_id BIGINT PRIMARY KEY, "
				+ "system_id VARCHAR(50), " + "smtp_id INT, " + "ip_address VARCHAR(50), " + "subject TEXT, "
				+ "body TEXT, " + "cc_recipients TEXT, " + "bcc_recipients TEXT, " + "attachments TEXT, "
				+ "total_recipients INT, " + "delay double(10,5) NOT NULL DEFAULT 0, " + "created_on TIMESTAMP, "
				+ "updated_on TIMESTAMP NULL, " + "status VARCHAR(20)" + ")";

		String insertSQL = "INSERT INTO " + tableName + " (batch_id, system_id, smtp_id, ip_address, subject, body,"
				+ " cc_recipients, bcc_recipients, attachments, total_recipients, delay,"
				+ " created_on, updated_on, status)" + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)";
		// updated_on always NULL

		try (Connection con = GlobalVar.connectionPool.getConnection();
				PreparedStatement createStmt = con.prepareStatement(createTableSQL);
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
			insertStmt.setString(13, entry.getBatchStatus().name());

			return insertStmt.executeUpdate() > 0;

		} catch (SQLException e) {
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
					return new EmailEntry(rs.getString("batch_id"), rs.getString("system_id"), rs.getInt("smtp_id"),
							rs.getString("ip_address"), rs.getString("subject"), rs.getString("body"),
							rs.getString("cc_recipients"), rs.getString("bcc_recipients"), rs.getString("attachments"),
							rs.getInt("total_recipients"), rs.getTimestamp("created_on"),
							EmailEntry.BatchStatus.valueOf(rs.getString("status")), rs.getDouble("delay"));
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
								rs.getInt("smtp_id"), rs.getString("ip_address"), rs.getString("subject"),
								rs.getString("body"), rs.getString("cc_recipients"), rs.getString("bcc_recipients"),
								rs.getString("attachments"), rs.getInt("total_recipients"),
								rs.getTimestamp("created_on"), EmailEntry.BatchStatus.valueOf(rs.getString("status")),
								rs.getDouble("delay")));
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
				list.add(new EmailEntry(rs.getString("batch_id"), rs.getString("system_id"), rs.getInt("smtp_id"),
						rs.getString("ip_address"), rs.getString("subject"), rs.getString("body"),
						rs.getString("cc_recipients"), rs.getString("bcc_recipients"), rs.getString("attachments"),
						rs.getInt("total_recipients"), rs.getTimestamp("created_on"),
						EmailEntry.BatchStatus.valueOf(rs.getString("status")), rs.getDouble("delay")));
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

	public void addPartition() {
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

}
