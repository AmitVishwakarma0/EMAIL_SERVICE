package com.hti.database.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.entity.RecipientsEntry;
import com.hti.entity.ReportEntry;
import com.hti.util.GlobalVar;
import com.hti.util.Queue;

public class RecipientEntryService implements Runnable {

	private Logger logger = LoggerFactory.getLogger("dbLogger");
	private boolean stop;
	private Queue processQueue;
	private String systemId;
	private String table_name = null;
	private boolean drop;

	public RecipientEntryService(String systemId, String batchId, Queue processQueue) {
		this.table_name = "recipient_" + systemId + "_" + batchId;
		logger.info(table_name + "_RecipientEntryService thread starting");
		this.systemId = systemId;
		this.processQueue = processQueue;
		new Thread(this, table_name + "_RecipientEntryService").start();
	}

	@Override
	public void run() {
		while (!stop) {
			if (processQueue.isEmpty()) {
				try {
					Thread.sleep(GlobalVar.QUEUE_WAIT_TIME);
				} catch (InterruptedException e) {
				}
				continue;
			}
			logger.info("processQueue: " + processQueue.size());
			try (Connection connection = GlobalVar.connectionPool.getConnection();
					PreparedStatement statement = connection
							.prepareStatement("UPDATE " + table_name + " set flag=? where msg_id = ?")) {
				connection.setAutoCommit(false);
				int count = 0;

				while (!processQueue.isEmpty()) {
					RecipientsEntry entry = (RecipientsEntry) processQueue.dequeue();
					statement.setString(1, entry.getFlag());
					statement.setString(2, entry.getMsgId());
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
		if (drop) {
			dropTable();
		}
		logger.info(table_name + "_RecipientEntryService Stopped.Queue:" + processQueue.size());
	}

	private void dropTable() {
		logger.info(table_name + " Drop Command Received");
		try (Connection connection = GlobalVar.connectionPool.getConnection();
				PreparedStatement statement = connection.prepareStatement("DROP TABLE IF EXISTS " + table_name)) {
			statement.execute();
			logger.info(table_name + " Table Drop Success.");
		} catch (SQLException e) {
			logger.error(systemId, e);
		} catch (Exception e) {
			logger.error(systemId, e);
		}
	}

	public void stop(boolean drop) {
		logger.info(table_name + "_RecipientEntryService Stopping.Queue:" + processQueue.size());
		this.drop = drop;
		stop = true;
	}

}
