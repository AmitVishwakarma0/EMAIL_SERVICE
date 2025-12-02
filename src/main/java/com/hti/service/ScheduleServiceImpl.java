package com.hti.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Service
public class ScheduleServiceImpl implements ScheduleService{

	@Override
	public String scheduleEmailMessages(@Valid ScheduleEmailRequest emailRequest, String username, String ipAddress) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public EmailScheduleResponse editSchedule(String username,
			@NotBlank(message = "IP address header is required") String ipAddress, String batchId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void abortSchedule(String username, @NotBlank(message = "IP address header is required") String ipAddress,
			String batchId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateSchedule(@Valid ScheduleEmailRequest emailRequest, String username, String ipAddress) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public List<EmailScheduleResponse> getAllScheduledBatches(String username,
			@NotBlank(message = "IP address header is required") String ipAddress,
			@Valid BatchProcessFilterRequest batchProcessFilterRequest) {
		// TODO Auto-generated method stub
		return null;
	}
}
