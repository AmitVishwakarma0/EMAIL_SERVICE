package com.hti.service;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.database.service.InboxService;
import com.hti.database.service.ReportService;
import com.hti.process.DlrForwarder;

public class SingletonService {

	private static Logger logger = LoggerFactory.getLogger(SingletonService.class);
	private static Map<String, ReportService> UserReportServiceCache = new HashMap<String, ReportService>();
	private static Map<String, DlrForwarder> UserDlrForwarderCache = new HashMap<String, DlrForwarder>();
	private static Map<String, InboxService> UserInboxInsertCache = new HashMap<String, InboxService>();

	public static ReportService getUserReportService(String systemId) {
		synchronized (UserReportServiceCache) {
			if (!UserReportServiceCache.containsKey(systemId)) {
				UserReportServiceCache.put(systemId, new ReportService(systemId));
			}
		}
		return UserReportServiceCache.get(systemId);
	}

	public static DlrForwarder getUserDlrForwarder(String systemId) {
		synchronized (UserDlrForwarderCache) {
			if (!UserDlrForwarderCache.containsKey(systemId)) {
				UserDlrForwarderCache.put(systemId, new DlrForwarder(systemId));
			}
		}
		return UserDlrForwarderCache.get(systemId);
	}
	
	public static InboxService getUserInboxService(String systemId) {
		synchronized (UserInboxInsertCache) {
			if (!UserInboxInsertCache.containsKey(systemId)) {
				UserInboxInsertCache.put(systemId, new InboxService(systemId));
			}
		}
		return UserInboxInsertCache.get(systemId);
	}
	
	
	public static void removeUserInboxService(String systemId) {
		synchronized (UserInboxInsertCache) {
			InboxService service = UserInboxInsertCache.remove(systemId);
			if (service != null) {
				service.stop();
			}
		}
	}

	public static void removeUserDlrForwarder(String systemId) {
		synchronized (UserDlrForwarderCache) {
			DlrForwarder forwarder = UserDlrForwarderCache.remove(systemId);
			if (forwarder != null) {
				forwarder.stop();
			}
		}
	}

	public static void removeUserReportService(String systemId) {
		synchronized (UserReportServiceCache) {
			ReportService reportService = UserReportServiceCache.remove(systemId);
			if (reportService != null) {
				reportService.stop();
			}
		}
	}

	public static void clear() {
		logger.info("<--- Stopping User Report Services -->");
		try {
			UserReportServiceCache.forEach((k, v) -> {
				v.stop();
			});
		} catch (Exception e) {
			logger.error("", e);
		}

		logger.info("<--- Stopping User Dlr Forwarder -->");
		try {
			UserDlrForwarderCache.forEach((k, v) -> {
				v.stop();
			});
		} catch (Exception e) {
			logger.error("", e);
		}
		
		logger.info("<--- Stopping User Inbox Insert Service -->");
		try {
			UserInboxInsertCache.forEach((k, v) -> {
				v.stop();
			});
		} catch (Exception e) {
			logger.error("", e);
		}
		UserReportServiceCache.clear();
		UserDlrForwarderCache.clear();
		UserInboxInsertCache.clear();

	}

}
