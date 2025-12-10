package com.hti.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hti.database.service.DBService;

@Component
public class ScheduledUtility {
	private Logger logger = LoggerFactory.getLogger(ScheduledUtility.class);

	@Scheduled(cron = "${scheduler.db.cleanup.cron}")
	public void runCleanup() {
		logger.info("<----- DB Cleanup Task Started --> ");
		new DBService().addUserReportPartition();
		new DBService().addUserInboxPartition();
	}

}
