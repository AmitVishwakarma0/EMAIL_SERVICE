package com.hti.entity;

import lombok.Data;

@Data
public class SmtpEntry {
	private int id;

	private String systemId;

	private String host;

	private int port;

	private String emailUser;

	private String emailPassword;
	
	private boolean verified;

	private EncryptionType encryptionType;

	public enum EncryptionType {
		SSL, STARTTLS, NONE
	}

}
