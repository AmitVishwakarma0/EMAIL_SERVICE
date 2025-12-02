package com.hti.entity;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReportEntry {

	private String msgId;
	private String batchId;
	private String recipient;
	private String status;
	private int statusCode;
	private String remarks;
	private Timestamp receivedOn;
	private Timestamp submitOn;
	

	

}
