package com.hti.util;

import com.hazelcast.core.HazelcastInstance;
import com.hti.database.ConnectionPool;
import com.hti.entity.ImapEntry;
import com.hti.entity.SmtpEntry;
import com.hti.process.EmailProcessor;
import com.hti.process.ImapIdleListener;
import com.hti.service.EventService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalVar {
	public static String FLAG_DIR = "flag";
	public static String CONFIG_DIR = "config";
	private static int INCREMNT_NUMBER = 1000;
	public static long QUEUE_WAIT_TIME = 100;
	public static int JDBC_BATCH_SIZE = 1000;
	public static String ATTACHMENT_DIR = "attachments";
	// --- smtp configuration -----
	public static String EMAIL_CC;
	public static String EMAIL_FROM;
	public static String EMAIL_USER;
	public static String EMAIL_PASSWORD;
	public static String SMTP_HOST_NAME;
	public static int SMTP_PORT;
	public static HazelcastInstance hazelInstance;
	public static ConnectionPool connectionPool;
	public static Map<String, Map<String, EmailProcessor>> processingMap = new ConcurrentHashMap<String, Map<String, EmailProcessor>>();
	public static Map<String, ImapIdleListener> ImapListenerMap = new ConcurrentHashMap<String, ImapIdleListener>();
	public static Map<String, Map<Integer, SmtpEntry>> SmtpEntries = new ConcurrentHashMap<String, Map<Integer, SmtpEntry>>();
	public static Map<String, Map<Integer, ImapEntry>> ImapEntries = new ConcurrentHashMap<String, Map<Integer, ImapEntry>>();
	public static EventService eventService = new EventService();

	public static synchronized String assignMessageId() {
		if (++INCREMNT_NUMBER > 9999) {
			INCREMNT_NUMBER = 1000;
		}
		return new SimpleDateFormat("yyMMddHHmmssSSS").format(new Date()) + "" + INCREMNT_NUMBER;
	}

}
