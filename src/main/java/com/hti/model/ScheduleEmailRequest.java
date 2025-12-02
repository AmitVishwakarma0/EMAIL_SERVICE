package com.hti.model;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ScheduleEmailRequest extends EmailRequest {

	@Schema(description = "GMT", example = "+05:30")
	private String gmt;

	@Schema(description = "Schedule Time ", example = "2025-05-12 13:10:10")
	private LocalDateTime scheduledOn;

	

}
