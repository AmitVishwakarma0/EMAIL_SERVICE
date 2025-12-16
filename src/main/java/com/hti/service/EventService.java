package com.hti.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.database.service.DBService;
import com.hti.entity.EmailEntry;
import com.hti.entity.ImapEntry;
import com.hti.entity.RecipientsEntry;
import com.hti.entity.SmtpEntry;
import com.hti.entity.EmailEntry.BatchStatus;
import com.hti.exception.InvalidRequestException;
import com.hti.process.EmailProcessor;
import com.hti.process.ImapIdleListener;
import com.hti.util.GlobalVar;

public class EventService {
	private static Logger logger = LoggerFactory.getLogger(EventService.class);
	private DBService dbService = new DBService();

	public void handleStart() {
		loadSmtpEntries();
		loadImapEntries();
		ExecutorService exec = Executors.newSingleThreadExecutor();
		exec.submit(() -> {
			try {
				loadPendingEntriesAsync();
				startImapListeners();
			} finally {
				exec.shutdown();
			}
		});
	}

	public void handleStop() {
		stopImapListeners();
		stopRunningBatches();
		SingletonService.clear();
	}

	public void setSmtpVerified(String systemId, int smtpId) {
		dbService.setSmtpVerified(smtpId);
		if (GlobalVar.SmtpEntries.containsKey(systemId)) {
			SmtpEntry entry = GlobalVar.SmtpEntries.get(systemId).get(smtpId);
			if (entry == null) {
				return;
			}
			if (!entry.isVerified()) {
				entry.setVerified(true);
				// check for imap
				if (entry.isReadInbox()) {
					String keyName = systemId + "_" + smtpId;
					if (!GlobalVar.ImapListenerMap.containsKey(keyName)) {
						ImapEntry imapEntry = dbService.loadImapEntry(systemId, smtpId);
						GlobalVar.ImapListenerMap.put(keyName, new ImapIdleListener(imapEntry));
					}
				}
			}

		}
	}

	private void startImapListeners() {
		logger.info("Checking for Imap Listeners");
		if (GlobalVar.ImapEntries.isEmpty()) {
			logger.info("No Imap Configuration Found.");
		}

		GlobalVar.ImapEntries.forEach((systemId, entryMap) -> {
			logger.info("Checking Imap Listeners for {}", systemId);
			entryMap.forEach((id, entry) -> {
				String keyName = systemId + "_" + id;
				GlobalVar.ImapListenerMap.put(keyName, new ImapIdleListener(entry));
			});
		});

		logger.info("End Checking Imap Listeners");
	}

	private void loadPendingEntriesAsync() {
		logger.info("Checking For PendingEntries");
		DBService service = new DBService();
		List<EmailEntry> list = service.listPendingEntries();

		if (list.isEmpty()) {
			logger.info("No Pending Batches Found");
			return;
		}
		ExecutorService entryExecutor = Executors.newFixedThreadPool(list.size());
		for (EmailEntry entry : list) {
			entryExecutor.submit(() -> processEntry(entry));
		}
		entryExecutor.shutdown(); // Stop accepting new tasks

		try {
			// Wait until all tasks complete (up to 60 seconds)
			if (!entryExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
				entryExecutor.shutdownNow(); // Force shutdown if still running
			}
		} catch (InterruptedException e) {
			entryExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		logger.info("End Checking For PendingEntries");
	}

	private void processEntry(EmailEntry entry) {
		try {
			DBService service = new DBService();
			List<RecipientsEntry> recipients = service.listPendingRecipients(entry.getSystemId(), entry.getBatchId());
			entry.setPendingRecipientList(recipients);
			EmailProcessor processor = new EmailProcessor(entry);
			GlobalVar.processingMap.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>())
					.put(entry.getBatchId(), processor);

		} catch (InvalidRequestException e) {
			logger.error(entry.getSystemId() + "[" + entry.getBatchId() + "]", e.getMessage());
		}
	}

	private void stopImapListeners() {
		logger.info("<--- Stopping Imap Listeners Services -->");
		try {
			GlobalVar.ImapListenerMap.forEach((k, v) -> {
				v.stop();
			});
		} catch (Exception e) {
			logger.error("", e);
		}

	}

	private void loadSmtpEntries() {
		logger.info("<----- Loading Smtp Entries --------> ");
		List<SmtpEntry> entries = dbService.loadSmtpEntries();
		if (entries.isEmpty()) {
			logger.info(" <-- No Smtp Entries Found -->  ");
			return;
		}
		logger.info("Total Smtp Entries Found: " + entries.size());
		for (SmtpEntry entry : entries) {
			GlobalVar.SmtpEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>())
					.put(entry.getId(), entry);
		}

		logger.info("<----- End Loading Smtp Entries ----> ");
	}

	private void loadImapEntries() {
		logger.info("<----- Loading Imap Entries --------> ");
		List<ImapEntry> entries = dbService.loadImapEntries();
		if (entries.isEmpty()) {
			logger.info(" <-- No Imap Entries Found -->  ");
			return;
		}
		logger.info("Total Imap Entries Found: " + entries.size());
		for (ImapEntry entry : entries) {
			GlobalVar.ImapEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>())
					.put(entry.getId(), entry);
		}

		logger.info("<----- End Loading Imap Entries ----> ");
	}

	public void handleSmtpUpdate(String systemId, int smtpId) {
		SmtpEntry entry = dbService.loadSmtpEntry(systemId, smtpId);
		if (entry == null) {
			return;
		}
		GlobalVar.SmtpEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>()).put(entry.getId(),
				entry);
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		if (inner == null) {
			logger.info(systemId + " No Running Batches.");
		} else {
			for (EmailProcessor processor : inner.values()) {
				String batchId = processor.getBatchId();
				if (processor.getSmtpEntry().getId() == smtpId) {
					logger.info(systemId + " Running Batch {} Found To Update Smtp Configuration {}", batchId, smtpId);
					try {
						processor.reloadSmtpConfiguration();
					} catch (InvalidRequestException e) {
						processor.stop(BatchStatus.ABORTED);
						logger.error(systemId + " " + batchId, e.getMessage());
					}
				}
			}

		}
		// check for imap
		String keyName = systemId + "_" + smtpId;
		if (entry.isVerified() && entry.isReadInbox()) {
			ImapEntry imapEntry = dbService.loadImapEntry(systemId, smtpId);
			GlobalVar.ImapEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>())
					.put(entry.getId(), imapEntry);
			if (GlobalVar.ImapListenerMap.containsKey(keyName)) {
				GlobalVar.ImapListenerMap.get(keyName).updateEntry(imapEntry);
			} else {
				GlobalVar.ImapListenerMap.put(keyName, new ImapIdleListener(imapEntry));
			}
		} else {
			if (GlobalVar.ImapListenerMap.containsKey(keyName)) {
				GlobalVar.ImapListenerMap.remove(keyName).stop();
			}
		}
	}

	public void handleSmtpAdd(String systemId, int smtpId) {
		SmtpEntry entry = dbService.loadSmtpEntry(systemId, smtpId);
		if (entry == null) {
			return;
		}
		GlobalVar.SmtpEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>()).put(entry.getId(),
				entry);
		// check for imap
		String keyName = systemId + "_" + smtpId;
		if (entry.isVerified() && entry.isReadInbox()) {
			ImapEntry imapEntry = dbService.loadImapEntry(systemId, smtpId);
			GlobalVar.ImapEntries.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>())
					.put(entry.getId(), imapEntry);
			GlobalVar.ImapListenerMap.put(keyName, new ImapIdleListener(imapEntry));
		}
	}

	public void handleSmtpRemove(String systemId, int smtpId) {
		if (GlobalVar.SmtpEntries.containsKey(systemId)) {
			GlobalVar.SmtpEntries.get(systemId).remove(smtpId);
		}
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		if (inner == null) {
			logger.info(systemId + " No Running Batches.");
		} else {
			for (EmailProcessor processor : inner.values()) {
				String batchId = processor.getBatchId();
				if (processor.getSmtpEntry().getId() == smtpId) {
					logger.info(systemId + " Running Batch {} Found To Remove Smtp Configuration {}", batchId, smtpId);
					processor.stop(BatchStatus.ABORTED);
				}
			}

		}
		if (GlobalVar.ImapEntries.containsKey(systemId)) {
			GlobalVar.ImapEntries.get(systemId).remove(smtpId);
		}
		String keyName = systemId + "_" + smtpId;
		if (GlobalVar.ImapListenerMap.containsKey(keyName)) {
			GlobalVar.ImapListenerMap.remove(keyName).stop();
		}
	}

	private void stopRunningBatches() {
		GlobalVar.processingMap.forEach((systemId, batchMap) -> {
			batchMap.forEach((batchId, processor) -> {
				try {
					processor.stop();
				} catch (Exception ignored) {
				}
			});
		});

		GlobalVar.processingMap.clear();
	}
}
