package com.hti.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.util.Queue;

public class DlrForwarder implements Runnable {
	private Logger logger = LoggerFactory.getLogger(DlrForwarder.class);
	private String systemId;
	private boolean stop;
	private Queue processQueue;

	public DlrForwarder(String systemId) {
		this.systemId = systemId;
		logger.info(systemId + "_DlrForwarder Starting.");
	}

	@Override
	public void run() {
		while (!stop) {
			if (processQueue.isEmpty()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				continue;
			}
			while (!processQueue.isEmpty()) {
				// proceed further
			}

		}
		logger.info(systemId + "_DlrForwarder Stopped.");
	}

	public void stop() {
		logger.info(systemId + "_DlrForwarder Stopping.");
		stop = true;
	}

}
