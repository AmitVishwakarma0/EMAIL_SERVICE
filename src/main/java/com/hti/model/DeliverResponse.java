package com.hti.model;


import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeliverResponse {

	private String batchId;

	private String msgId;

	private int smtpId;

	private String subject;

	private String recipient;

	private String status;

	private String deliverOn;
	
	private transient String url;
}
