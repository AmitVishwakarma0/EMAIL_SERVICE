package com.hti.util;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hti.database.service.DBService;
import com.hti.entity.ImapEntry;
import com.hti.entity.SmtpEntry;
import com.hti.process.InboxReaderThread;

@Component
public class ScheduledUtility {
	private Logger logger = LoggerFactory.getLogger(ScheduledUtility.class);

	@Scheduled(cron = "${scheduler.db.cleanup.cron}")
	public void runCleanup() {
		logger.info("<----- DB Cleanup Task Started --> ");
		new DBService().addUserReportPartition();
		new DBService().addUserInboxPartition();
	}

	@Scheduled(cron = "${scheduler.inbox.read.cron}")
	public void readInbox() {
		logger.info("<----- Inbox Reading Task Started -->");
		List<ImapEntry> entries = new DBService().listImapEntries();
		if (entries.isEmpty()) {
			logger.info("<----- No Inbox Reading Task Found -->");
			return;
		}
		logger.info("Total Imap Entries Found To Proceed: " + entries.size());
		for (ImapEntry config : entries) {
			String key = config.getSystemId() + "_" + config.getId();
			new Thread(new InboxReaderThread(config), "InboxReader_" + key).start();
			logger.info("Started inbox reader for SMTP: {}", key);
		}
	}

}
