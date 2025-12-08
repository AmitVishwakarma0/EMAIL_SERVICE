package com.hti.process;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.hti.model.DeliverResponse;
import com.hti.service.SingletonService;
import com.hti.util.Queue;

public class DlrForwarder implements Runnable {
	private Logger logger = LoggerFactory.getLogger(DlrForwarder.class);
	private String systemId;
	private boolean stop;
	private Queue processQueue;
	private final ExecutorService webhookExecutor = Executors.newFixedThreadPool(3);
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final Gson gson = new Gson();
	private long lastActiveTime;
	private static final long IDLE_TIMEOUT = 600_000; // 10 minutes

	public DlrForwarder(String systemId) {
		this.systemId = systemId;
		this.processQueue = new Queue();
		logger.info(systemId + "_DlrForwarder Starting.");
		this.lastActiveTime = System.currentTimeMillis(); // reset idle timer
		new Thread(this, systemId + "_DlrForwarder").start();
	}

	public void submit(DeliverResponse deliver) {
		processQueue.enqueue(deliver);
	}

	@Override
	public void run() {
		while (!stop) {
			if (processQueue.isEmpty()) {
				long idleFor = System.currentTimeMillis() - lastActiveTime;

				if (idleFor > IDLE_TIMEOUT) {
					logger.info(systemId + "_DlrForwarder Idle timeout. Auto-stopping.");
					SingletonService.removeUserDlrForwarder(systemId); // remove from cache
					break;
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				continue;
			}
			lastActiveTime = System.currentTimeMillis(); // reset idle timer
			while (!processQueue.isEmpty()) {
				DeliverResponse response = (DeliverResponse) processQueue.dequeue();
				sendWebhookAsync(response);
			}

		}
		webhookExecutor.shutdownNow();
		logger.info(systemId + "_DlrForwarder Stopped.");
	}

	private void sendWebhookAsync(DeliverResponse response) {
		webhookExecutor.submit(() -> {
			try {
				String json = gson.toJson(response);
				logger.info(systemId + " webhook post: " + json.toString());
				HttpRequest request = HttpRequest.newBuilder().uri(URI.create(response.getUrl()))
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
						.build();
				HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				logger.info(systemId + " Webhook POST to " + response.getUrl() + " status=" + httpResponse.statusCode()
						+ " msgId=" + response.getMsgId());
			} catch (Exception e) {
				logger.error(systemId + " Webhook send failed for " + response.getMsgId(), e);
			}
		});
	}

	public void stop() {
		logger.info(systemId + "_DlrForwarder Stopping.");
		stop = true;
	}

}
