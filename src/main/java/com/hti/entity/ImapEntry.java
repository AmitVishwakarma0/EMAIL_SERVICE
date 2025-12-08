package com.hti.entity;


public class ImapEntry extends SmtpEntry{
	
	public ImapEntry(int id, String systemId, String host, int port, String emailUser, String emailPassword,
			EncryptionType encryptionType) {
		setId(id);
		setSystemId(systemId);
		setHost(host);
		setPort(port);
		setEmailUser(emailUser);
		setEmailPassword(emailPassword);
		setEncryptionType(encryptionType);
	}
}
