package com.hti.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Service;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.topic.ITopic;
import com.hti.database.ConnectionPool;
import com.hti.database.service.DBService;
import com.hti.entity.EmailEntry;
import com.hti.entity.RecipientsEntry;
import com.hti.exception.InvalidRequestException;
import com.hti.listener.FlagEventListener;
import com.hti.process.EmailProcessor;
import com.hti.process.SchedulerManager;
import com.hti.util.FileUtil;
import com.hti.util.GlobalVar;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

@Service
public class ServiceController implements Runnable {

	private Logger logger = org.slf4j.LoggerFactory.getLogger(ServiceController.class);

	private String APPLICATION_FLAG = null;
	private long mb = 1024 * 1024;
	private Runtime runtime = Runtime.getRuntime();
	private long used_memory;
	private long max_memory;

	public ServiceController() {
		super();
	}

	public void startService() throws Exception {
		configureLogger();
		loadConfiguration();
		initializeGlobalVars();
		FileUtil.setDefaultFlag(GlobalVar.FLAG_DIR + "//Application.flag");
		 ExecutorService exec = Executors.newSingleThreadExecutor();
		    exec.submit(() -> {
		        try {
		            loadPendingEntriesAsync();
		        } finally {
		            exec.shutdown();
		        }
		    });
		new Thread(this, "Monitor").start();
	}

	@Override
	public void run() {
		try {
			while (true) {
				logger.info("<-- Email Service Running --> ");
				APPLICATION_FLAG = FileUtil.readFlag(GlobalVar.FLAG_DIR + "//Application.flag");
				if (APPLICATION_FLAG.equalsIgnoreCase("404")) {
					System.out.println("*******************************************");
					System.out.println("*******************************************");
					System.out.println("**** Application Flag Status Blocked ******");
					System.out.println("*******************************************");
					System.out.println("*******************************************");
					break;
				} else if (APPLICATION_FLAG.equalsIgnoreCase("707")) {
					FileUtil.setDefaultFlag(GlobalVar.FLAG_DIR + "//Application.flag");
					loadConfiguration();
				} else {
					checkMemoryUsage();
					try {
						Thread.sleep(10 * 1000);
					} catch (InterruptedException ie) {
					}
				}
			}
		} catch (Exception ie) {
			ie.printStackTrace();
		} finally {
			logger.info("<-- Received Command To Stop Service --> ");
			stopProcess();
		}
		logger.info("<-- Exiting --> ");
		System.exit(0);

	}

	private void checkMemoryUsage() {
		used_memory = (runtime.totalMemory() - runtime.freeMemory()) / mb;
		max_memory = (runtime.maxMemory() / mb);
		logger.info("Memory Used:---> " + used_memory + " MB. Max Available: " + max_memory + " MB");
	}

	private void initializeGlobalVars() throws Exception {
		ClientConfig config = new ClientConfig();
		config.getNetworkConfig().setSmartRouting(false);
		GlobalVar.hazelInstance = HazelcastClient.newHazelcastClient(config);
		ITopic<Map<String, String>> flag_topic = GlobalVar.hazelInstance.getTopic("flag_status");
		flag_topic.addMessageListener(new FlagEventListener());
		GlobalVar.connectionPool = new ConnectionPool();
	}

	private void configureLogger() throws JoranException, IOException {
		LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
		loggerContext.reset();
		JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(loggerContext);
		try (InputStream inputStream = FileUtil.class.getClassLoader().getResourceAsStream("logback-spring.xml")) {
			if (inputStream == null) {
				throw new FileNotFoundException("<--- log Configuration File Missing --> ");
			}
			configurator.doConfigure(inputStream);
		} catch (IOException e) {
			throw new IOException(e.getMessage());
		}

	}

	private void loadConfiguration() throws IOException {
		Properties props = new Properties();
		File configFile = new File(GlobalVar.CONFIG_DIR + "//application.properties");
		String propertyPath = "classpath:application.properties";
		if (configFile.exists()) {
			propertyPath = configFile.getPath();
		}
		logger.info("******* Loading properties from path: " + propertyPath + " ********");

		try (InputStream inputStream = new FileInputStream(propertyPath)) {
			props.load(inputStream);
			logger.info("Properties successfully loaded from: " + propertyPath);
		} catch (FileNotFoundException e) {
			logger.warn("Property file not found at specified path: " + propertyPath + ", loading from classpath.");
			props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
		} catch (IOException e) {
			logger.error("Error loading properties file from: " + propertyPath, e);
			throw e;
		}
		GlobalVar.ATTACHMENT_DIR = props.getProperty("attachment.dir");
		GlobalVar.QUEUE_WAIT_TIME = Integer.parseInt(props.getProperty("queue.wait.time"));
		GlobalVar.JDBC_BATCH_SIZE = Integer.parseInt(props.getProperty("jdbc.batch.size"));
		// ------- smtp configuration -----------------
		GlobalVar.EMAIL_CC = props.getProperty("smtp.mail.cc");
		GlobalVar.EMAIL_FROM = props.getProperty("smtp.mail.from");
		GlobalVar.EMAIL_USER = props.getProperty("smtp.mail.user");
		GlobalVar.EMAIL_PASSWORD = props.getProperty("smtp.mail.password");
		GlobalVar.SMTP_HOST_NAME = props.getProperty("smtp.mail.host");
		GlobalVar.SMTP_PORT = Integer.parseInt(props.getProperty("smtp.mail.port"));

		logger.info("Configuration loaded successfully.");
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
	

	private void stopProcess() {
		// TODO Auto-generated method stub

	}

}
