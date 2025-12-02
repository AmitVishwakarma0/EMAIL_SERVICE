package com.hti.model;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class EmailProcessResponse extends EmailRequest {
	
	private LocalDateTime createdOn;
	private int totalRecipients;
	private int pendingCounter;
	private String status;
}
