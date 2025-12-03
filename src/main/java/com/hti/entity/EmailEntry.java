package com.hti.entity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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

	private BatchType batchType;

	public enum BatchStatus {
		ACTIVE, PAUSED, ABORTED, FINISHED
	}

	public enum BatchType {
		IMMEDIATE, SCHEDULED
	}
	
	public EmailEntry(String batchId, String systemId, String ipAddress) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
	}

	public EmailEntry(String batchId, String systemId, String ipAddress, Timestamp createdOn) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
		this.createdOn = createdOn;
	}

	public EmailEntry(String batchId, String systemId, String ipAddress, Timestamp createdOn, BatchStatus batchStatus,
			BatchType batchType) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
		this.createdOn = createdOn;
		this.batchStatus = batchStatus;
		this.batchType = batchType;
	}

	public EmailEntry(String batchId, String systemId, String ipAddress, int smtpId, String subject, String body,
			String ccRecipients, String bccRecipients, String attachments, double delay, int totalRecipients,
			Timestamp createdOn) {
		this.batchId = batchId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
		this.smtpId = smtpId;
		this.subject = subject;
		this.body = body;
		this.ccRecipients = ccRecipients;
		this.bccRecipients = bccRecipients;
		this.attachments = attachments;
		this.delay = delay;
		this.totalRecipients = totalRecipients;
		this.createdOn = createdOn;
	}

	public EmailEntry(String batchId, String systemId, int smtpId, String ipAddress, String subject, String body,
			String ccRecipients, String bccRecipients, String attachments, int totalRecipients, Timestamp createdOn,
			BatchStatus batchStatus, double delay, BatchType batchType) {
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
		this.batchType = batchType;
	}

}
