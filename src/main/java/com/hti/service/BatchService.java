package com.hti.service;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.EmailRequest;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public interface BatchService {

	public String sendEmailMessages(EmailRequest emailRequest, String systemId, String ipAddress);

	public EmailProcessResponse editBulk(String systemId, String ipAddress, String batchId);

	public void pauseBulk(String systemId, String ipAddress, String batchId);

	public void abortBulk(String systemId, String ipAddress, String batchId);

	public void resumeBulk(String systemId, String ipAddress, String batchId);

	public void resumeBulk(EmailRequest emailRequest, String systemId, String ipAddress);

	public List<EmailProcessResponse> getAllBulk(String systemId, String ipAddress,
			BatchProcessFilterRequest batchProcessFilterRequest);


}
