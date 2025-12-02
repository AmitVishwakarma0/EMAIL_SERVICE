package com.hti.entity;


import java.sql.Timestamp;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailEntry {

	private String batchId;

	private int smtpId;

	private String systemId;

	private String ipAddress;

	private String subject;

	private String body;

	private String ccRecipients;

	private String bccRecipients;

	private String attachments; // JSON array of file names

	private double delay;

	private Timestamp createdOn;

	private Timestamp updatedOn;

	private int totalRecipients;

	private List<RecipientsEntry> pendingRecipientList;

	private BatchStatus batchStatus;

	public enum BatchStatus {
		ACTIVE, PAUSED, ABORTED, FINISHED
	}

	public EmailEntry(String batchId, String systemId, String ipAddress, Timestamp createdOn, BatchStatus batchStatus) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
		this.createdOn = createdOn;
		this.batchStatus = batchStatus;
	}

	public EmailEntry(String batchId, String systemId, int smtpId, String ipAddress, String subject, String body,
			String ccRecipients, String bccRecipients, String attachments, int totalRecipients, Timestamp createdOn,
			BatchStatus batchStatus, double delay) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.smtpId = smtpId;
		this.ipAddress = ipAddress;
		this.subject = subject;
		this.body = body;
		this.ccRecipients = ccRecipients;
		this.bccRecipients = bccRecipients;
		this.attachments = attachments;
		this.totalRecipients = totalRecipients;
		this.createdOn = createdOn;
		this.batchStatus = batchStatus;
		this.delay = delay;
	}

}
