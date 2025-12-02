package com.hti.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RecipientsEntry {
	private String msgId;
	private String recipient;
	private String flag;

	public RecipientsEntry(String msgId, String recipient) {
		this.msgId = msgId;
		this.recipient = recipient;
	}
}
