package com.hti.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hti.database.service.DBService;

@Component
public class CleanupUtility {
	private Logger logger = LoggerFactory.getLogger("dbLogger");

	@Scheduled(cron = "${scheduler.db.cleanup.cron}")
	public void runCleanup() {
		logger.info("<----- Cleanup Thread Started --> ");
		new DBService().addPartition();
	}
}
