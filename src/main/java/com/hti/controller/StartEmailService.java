package com.hti.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

@SpringBootApplication(scanBasePackages = "com.hti")
@EnableScheduling
public class StartEmailService {
	private static Logger logger = LoggerFactory.getLogger(StartEmailService.class);
	@Autowired
	private ServiceController serviceController;


	public static void main(String[] args) {
		logger.info("<--- Email Service Starting  --->");
		SpringApplication.run(StartEmailService.class, args);
	}

	@PostConstruct
	private void StartThread() throws Exception {
		serviceController.startService();
	}

}
