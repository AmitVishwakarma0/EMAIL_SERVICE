package com.hti.entity;

import java.sql.Timestamp;

import lombok.Data;

@Data
public class SandBoxEmailEntry {

	private String msgId;

	private int smtpId;

	private String systemId;

	private String ipAddress;

	private String subject;

	private String body;

	private String recipient;

	private String ccRecipients;

	private String bccRecipients;

	private String attachments; // JSON array of file names

	private Timestamp createdOn;

	public SandBoxEmailEntry(String msgId, String systemId, String ipAddress, Timestamp createdOn) {
		super();
		this.msgId = msgId;
		this.systemId = systemId;
		this.ipAddress = ipAddress;
		this.createdOn = createdOn;
	}

}
