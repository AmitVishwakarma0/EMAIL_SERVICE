package com.hti.service;

import java.util.List;

import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;
import com.hti.model.ScheduleFilterRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public interface ScheduleService {

	public String scheduleEmailMessages(@Valid ScheduleEmailRequest emailRequest, String username, String ipAddress);

	public EmailScheduleResponse editSchedule(String username,
			@NotBlank(message = "IP address header is required") String ipAddress, String batchId);

	public void abortSchedule(String username, @NotBlank(message = "IP address header is required") String ipAddress,
			String batchId);

	public void updateSchedule(@Valid ScheduleEmailRequest emailRequest, String username, String ipAddress);

	public List<EmailScheduleResponse> getAllScheduledBatches(String username, String ipAddress,
			@Valid ScheduleFilterRequest filterRequest);
}
