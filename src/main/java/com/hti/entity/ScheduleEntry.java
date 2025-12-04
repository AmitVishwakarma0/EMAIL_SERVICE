package com.hti.entity;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import com.hti.entity.EmailEntry.BatchStatus;

import lombok.Data;

@Data
public class ScheduleEntry extends EmailEntry {

	private String gmt;
	private LocalDateTime scheduledOn;
	private LocalDateTime serverTime;

	public ScheduleEntry(String batchId, String systemId, String ipAddress) {
		super(batchId, systemId, ipAddress);
	}

	public ScheduleEntry(String batchId, String systemId, String ipAddress, Timestamp timestamp) {
		super(batchId, systemId, ipAddress, timestamp);
	}

	public ScheduleEntry(String batchId, String systemId, String ipAddress, int smtpId, String subject, String body,
			String ccRecipients, String bccRecipients, String attachments, double delay, int totalRecipients,
			Timestamp createdOn, LocalDateTime serverTime, String gmt, LocalDateTime scheduledOn,
			BatchStatus batchStatus) {
		super(batchId, systemId, ipAddress, smtpId, subject, body, ccRecipients, bccRecipients, attachments, delay,
				totalRecipients, createdOn, batchStatus);
		this.serverTime = serverTime;
		this.gmt = gmt;
		this.scheduledOn = scheduledOn;
	}

}
