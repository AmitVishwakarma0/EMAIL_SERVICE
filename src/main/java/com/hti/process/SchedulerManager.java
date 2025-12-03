package com.hti.process;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hti.database.service.DBService;
import com.hti.entity.EmailEntry;
import com.hti.entity.EmailEntry.BatchStatus;
import com.hti.entity.EmailEntry.BatchType;
import com.hti.exception.ProcessingException;
import com.hti.entity.RecipientsEntry;
import com.hti.entity.ScheduleEntry;
import com.hti.util.GlobalVar;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class SchedulerManager {

	private Logger logger = LoggerFactory.getLogger(SchedulerManager.class);

	private DBService dbService = new DBService();

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

	// <systemId , Map<batchId , ScheduledFuture>>
	private final ConcurrentHashMap<String, ConcurrentHashMap<String, ScheduledFuture<?>>> batchMap = new ConcurrentHashMap<>();

	@PostConstruct
	public void start() {
		loadTodaySchedules();
		scheduleDailyLoader();
	}

	@PreDestroy
	public void shutdown() {
		scheduler.shutdownNow();
	}

	/**
	 * ------------------------- Load schedules for today -------------------------
	 */
	private void loadTodaySchedules() {
		logger.info("Loading today's schedules...");
		List<ScheduleEntry> entries = dbService.loadTodaySchedules();
		for (ScheduleEntry entry : entries) {
			scheduleBatch(entry);
		}
	}

	/**
	 * ------------------------- Schedule a batch for execution
	 * -------------------------
	 */
	public void scheduleBatch(ScheduleEntry entry) {
		String systemId = entry.getSystemId();
		String batchId = entry.getBatchId();
		LocalDateTime serverTime = entry.getServerTime();

		long delayMillis = Duration.between(LocalDateTime.now(), serverTime).toMillis();
		if (delayMillis < 0) {
			delayMillis = 0; // run immediately
		}

		ScheduledFuture<?> future = scheduler.schedule(() -> executeBatch(entry), delayMillis, TimeUnit.MILLISECONDS);

		batchMap.computeIfAbsent(systemId, k -> new ConcurrentHashMap<>()).put(batchId, future);

		logger.info("Scheduled Batch => SystemId: {} BatchId: {} Time: {}", systemId, batchId, serverTime);
	}

	/**
	 * ------------------------- Execute and clean up batch
	 * -------------------------
	 */
	private void executeBatch(ScheduleEntry entry) {
		String systemId = entry.getSystemId();
		String batchId = entry.getBatchId();

		try {
			logger.info("Executing batch {}", batchId);
			processEmailBatch(entry);

		} catch (Exception e) {
			logger.error("Error executing batch {}", batchId, e);

		} finally {
			// Remove entry properly from systemId → batchId map
			Map<String, ScheduledFuture<?>> systemMap = batchMap.get(systemId);
			if (systemMap != null) {
				systemMap.remove(batchId);
				if (systemMap.isEmpty()) {
					batchMap.remove(systemId);
				}
			}
			logger.info("Batch {} removed after execution", batchId);
		}
	}

	private void processEmailBatch(ScheduleEntry scheduleEntry) {
		String systemId = scheduleEntry.getSystemId();
		String batchId = scheduleEntry.getBatchId();
		logger.info(systemId + " Processing Email Batch => {}", batchId);
		EmailEntry entry = scheduleEntry;
		entry.setBatchType(BatchType.SCHEDULED);
		entry.setBatchStatus(BatchStatus.ACTIVE);
		List<String> recipients = dbService.listScheduledRecipients(systemId, batchId);
		List<RecipientsEntry> recipientsEntries = new ArrayList<RecipientsEntry>();
		for (String recipient : recipients) {
			recipientsEntries.add(new RecipientsEntry(GlobalVar.assignMessageId(), recipient, "F"));
		}
		entry.setPendingRecipientList(recipientsEntries);
		if (!dbService.createBatchEntry(entry)) {
			logger.error(systemId + "[" + batchId + "]: Batch Entry Creation Failed.");
			throw new ProcessingException("Batch Entry Creation Failed");
		}
		logger.info(systemId + "[" + batchId + "]: Batch Entry Created.");
		if (!dbService.saveRecipientsEntry(recipientsEntries, systemId, batchId)) {
			logger.error(systemId + "[" + batchId + "]: Recipients Entry Creation Failed.");
			throw new ProcessingException("Recipients Entry Creation Failed");
		}
		EmailProcessor processor = new EmailProcessor(entry);
		GlobalVar.processingMap.computeIfAbsent(systemId, k -> new ConcurrentHashMap<>()).put(batchId, processor);
		dbService.clearScheduleEntry(systemId, batchId);

	}

	public void cancelSchedule(String systemId, String batchId) {
		Map<String, ScheduledFuture<?>> inner = batchMap.get(systemId);
		if (inner == null)
			return;

		ScheduledFuture<?> future = inner.remove(batchId);

		if (future != null) {
			boolean cancelled = future.cancel(false);
			logger.info("Cancel Schedule {} => {}", batchId, cancelled ? "SUCCESS" : "FAILED");

			if (inner.isEmpty()) {
				batchMap.remove(systemId);
			}
		}
	}

	private void scheduleDailyLoader() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
		long initialDelay = Duration.between(now, nextMidnight).toMillis();

		scheduler.scheduleAtFixedRate(this::loadTodaySchedules, initialDelay, TimeUnit.DAYS.toMillis(1),
				TimeUnit.MILLISECONDS);

		logger.info("Daily midnight loader scheduled.");
	}

}
