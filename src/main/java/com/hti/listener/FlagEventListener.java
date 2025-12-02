package com.hti.listener;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.topic.Message;
import com.hazelcast.topic.MessageListener;
import com.hti.exception.InvalidRequestException;
import com.hti.process.EmailProcessor;
import com.hti.util.GlobalVar;

public class FlagEventListener implements MessageListener<Map<String, String>> {

	private Logger logger = LoggerFactory.getLogger(FlagEventListener.class);

	@Override
	public void onMessage(Message<Map<String, String>> message) {
		Map<String, String> data = message.getMessageObject();
		if (!data.containsKey("SMTP-CONFIG")) {
			return;
		}
		String status = data.get("SMTP-CONFIG");
		int smtpId = Integer.parseInt(data.get("SMTP_ID"));
		String systemId = data.get("SYSTEM_ID");
		logger.info(systemId + " Received event: {} | SMTP_ID: {}", status, smtpId);

		switch (status) {

		case "SMTP_ADD":
			logger.info(systemId + " smtp config {} added .", smtpId);
			break;

		case "SMTP_UPDATE":
			Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
			if (inner == null) {
				logger.info(systemId + " No Running Batches.");
			} else {
				for (EmailProcessor processor : inner.values()) {
					String batchId = processor.getBatchId();
					if (processor.getSmtpEntry().getId() == smtpId) {
						logger.info(systemId + " Running Batch {} Found To Update Smtp Configuration {}", batchId,
								smtpId);
						try {
							processor.reloadSmtpConfiguration();
						} catch (InvalidRequestException e) {
							logger.error(systemId + " " + batchId, e.getMessage());
						}
					}
				}

			}

			break;

		case "SMTP_DELETE":
			logger.info(systemId + " smtp config {} deleted .", smtpId);
			break;

		default:
			logger.warn(systemId + " Unknown event: {} | SMTP_ID: {}", status, smtpId);
			break;
		}
	}

}
