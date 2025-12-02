package com.hti.service;

import com.hti.model.SandBoxEmailRequest;


public interface SandboxService {

	public String sendEmail(SandBoxEmailRequest sandBoxEmailRequest, String ipAddress, String username);

}