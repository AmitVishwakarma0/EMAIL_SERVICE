package com.hti.model;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ScheduleFilterRequest {
	@Schema(description = "Batch Id Received On Submit", example = "2511261601534601001")
	private long batchId;
	@Schema(description = "SMTP Server Id", example = "1")
	private int smtpId;
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@Schema(description = "start time ", example = "2025-11-26 11:31:21")
	private LocalDateTime startTime;
	@Schema(description = "end time ", example = "2025-11-27 11:31:21")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime endTime;
}
