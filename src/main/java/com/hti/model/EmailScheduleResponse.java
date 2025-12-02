package com.hti.model;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class EmailScheduleResponse extends ScheduleEmailRequest {
	private LocalDateTime createdOn;
	private int totalRecipients;
}
