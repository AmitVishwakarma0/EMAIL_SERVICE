package com.hti.service;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.hazelcast.internal.json.JsonArray;
import com.hti.database.service.DBService;
import com.hti.entity.EmailEntry;
import com.hti.entity.EmailEntry.BatchStatus;
import com.hti.entity.ScheduleEntry;
import com.hti.exception.InvalidRequestException;
import com.hti.exception.ProcessingException;
import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;
import com.hti.model.ScheduleFilterRequest;
import com.hti.process.SchedulerManager;
import com.hti.util.DiskMultipartFile;
import com.hti.util.GlobalVar;

import jakarta.validation.Valid;

@Service
public class ScheduleServiceImpl implements ScheduleService {

	@Autowired
	private SchedulerManager schedulerManager;
	private DBService dbService = new DBService();
	private Logger logger = LoggerFactory.getLogger(ScheduleServiceImpl.class);

	@Override
	public String scheduleEmailMessages(ScheduleEmailRequest emailRequest, String systemId, String ipAddress) {
		String batchId = GlobalVar.assignMessageId();
		logger.info("Received Schedule Id generated: " + batchId + " " + emailRequest.getAttachmentList());
		emailRequest.setBatchId(batchId);
		ScheduleEntry entry = prepareEntry(emailRequest, systemId, ipAddress);
		if (entry.getServerTime().toLocalDate().isEqual(LocalDate.now())) { // check if its current day schedule
			schedulerManager.scheduleBatch(entry);
		}
		return batchId;
	}

	@Override
	public EmailScheduleResponse editSchedule(String systemId, String ipAddress, String batchId) {
		ScheduleEntry entry = dbService.getScheduleEntry(systemId, batchId);
		if (entry == null) {
			throw new InvalidRequestException(batchId + " No Scheduled Entry Found");
		}
		if (entry.getBatchStatus() != BatchStatus.PENDING) {
			throw new InvalidRequestException(
					batchId + " Scheduled Entry[" + String.valueOf(entry.getBatchStatus()) + "] Not Editable");
		}
		schedulerManager.cancelSchedule(systemId, batchId);
		return prepareResponse(entry, true);
	}

	@Override
	public void abortSchedule(String systemId, String ipAddress, String batchId) {
		if (!dbService.abortSchedule(systemId, batchId)) {
			throw new ProcessingException(batchId + " Abort Schedule Failed.");
		}
		schedulerManager.cancelSchedule(systemId, batchId);
	}

	@Override
	public void updateSchedule(ScheduleEmailRequest emailRequest, String systemId, String ipAddress) {
		String batchId = emailRequest.getBatchId();
		ScheduleEntry entry = dbService.getScheduleEntry(systemId, batchId);
		if (entry == null) {
			throw new InvalidRequestException("No Scheduled Entry Found");
		}
		updateEntry(emailRequest, entry);
		if (!dbService.updateScheduleEntry(entry)) {
			throw new ProcessingException(batchId + " Update Schedule Failed.");
		}
		if (entry.getServerTime().toLocalDate().isEqual(LocalDate.now())) { // check if its current day schedule
			schedulerManager.scheduleBatch(entry);
		}
	}

	@Override
	public List<EmailScheduleResponse> getAllScheduledBatches(String systemId, String ipAddress,
			ScheduleFilterRequest filterRequest) {
		List<EmailScheduleResponse> responseList = new ArrayList<EmailScheduleResponse>();
		List<ScheduleEntry> list = dbService.listSchedules(systemId, filterRequest);
		for (ScheduleEntry entry : list) {
			responseList.add(prepareResponse(entry, false));
		}
		return responseList;
	}

	private EmailScheduleResponse prepareResponse(ScheduleEntry entry, boolean attachment) {
		EmailScheduleResponse response = new EmailScheduleResponse();
		BeanUtils.copyProperties(entry, response);
		response.setStatus(entry.getBatchStatus().name());
		if (attachment) {
			List<MultipartFile> attachmentList = new ArrayList<MultipartFile>();
			if (entry.getAttachments() != null && !entry.getAttachments().isEmpty()) {
				JSONArray attachmentArray = new JSONArray(entry.getAttachments());

				for (int i = 0; i < attachmentArray.length(); i++) {
					String filepath = attachmentArray.getString(i).trim();
					File file = new File(filepath);
					if (file.exists()) {
						try {
							MultipartFile multipartFile = new DiskMultipartFile(file);
							attachmentList.add(multipartFile);
							logger.info(entry.getBatchId() + ": " + filepath + " found as attachment.");
						} catch (IOException e) {
							logger.error(entry.getBatchId() + " " + filepath, e);
						}
					} else {
						logger.warn(entry.getBatchId() + ": " + filepath + " does not exist.");
					}
				}
			}
			response.setAttachmentList(attachmentList);
		}
		return response;
	}

	private ScheduleEntry prepareEntry(@Valid ScheduleEmailRequest request, String systemId, String ipAddress) {
		String batchId = request.getBatchId();
		logger.info(systemId + "[" + batchId + "]: Preparing ScheduleEntry.");
		ScheduleEntry entry = new ScheduleEntry(batchId, systemId, ipAddress,
				new Timestamp(System.currentTimeMillis()));
		BeanUtils.copyProperties(request, entry);
		entry.setServerTime(convertToServerTime(request.getGmt(), request.getScheduledOn()));
		List<MultipartFile> attachmentList = request.getAttachmentList();
		if (attachmentList != null) {
			JsonArray json = new JsonArray();
			String attachmentDir = GlobalVar.ATTACHMENT_DIR + File.separator + systemId.toLowerCase() + File.separator
					+ "batch" + File.separator + batchId + File.separator;
			System.out.println("attachmentDir: " + attachmentDir);
			File dir = new File(System.getProperty("user.dir") + File.separator + attachmentDir);
			if (!dir.exists()) {
				dir.mkdirs(); // create directories
			}
			for (MultipartFile file : attachmentList) {
				String filename = writeToFile(file, dir);
				if (filename != null) {
					json.add(attachmentDir + filename); // add filename to JSON array
					logger.info(systemId + "[" + batchId + "]: " + filename + " Added As Attachment.");
				}
			}
			entry.setAttachments(json.toString()); // store JSON string
		}
		List<String> recipients = parseRecipients(request.getRecipients());
		if (recipients.isEmpty()) {
			logger.error(systemId + "[" + batchId + "]: No Valid Recipient Found.");
			throw new InvalidRequestException("No Valid Recipient Found");
		}
		entry.setTotalRecipients(recipients.size());
		if (!dbService.createScheduleEntry(entry)) {
			logger.error(systemId + "[" + batchId + "]: Schedule Entry Creation Failed.");
			throw new ProcessingException("Schedule Entry Creation Failed");
		}
		logger.info(systemId + "[" + batchId + "]: Schedule Entry Created.");
		if (!dbService.saveScheduledRecipients(recipients, systemId, batchId)) {
			logger.error(systemId + "[" + batchId + "]: Recipients Entry Creation Failed.");
			throw new ProcessingException("Recipients Entry Creation Failed");
		}
		logger.error(systemId + "[" + batchId + "] Recipients Entries:" + recipients.size());
		return entry;
	}

	private void updateEntry(ScheduleEmailRequest emailRequest, ScheduleEntry entry) {
		entry.setSubject(emailRequest.getSubject());
		entry.setBody(emailRequest.getBody());
		entry.setCcRecipients(emailRequest.getCcRecipients());
		entry.setBccRecipients(emailRequest.getBccRecipients());
		entry.setDelay(emailRequest.getDelay());
		entry.setSmtpId(emailRequest.getSmtpId());
		entry.setServerTime(convertToServerTime(emailRequest.getGmt(), emailRequest.getScheduledOn()));
	}

	public LocalDateTime convertToServerTime(String gmt, LocalDateTime scheduledOn) {

		ZoneId userZone = ZoneId.of("GMT" + gmt); // +05:30 → GMT+05:30
		ZoneId serverZone = ZoneId.systemDefault(); // Server's machine timezone

		// Attach user timezone
		ZonedDateTime userZoned = scheduledOn.atZone(userZone);

		// Convert to server timezone
		ZonedDateTime serverZoned = userZoned.withZoneSameInstant(serverZone);

		// Return LocalDateTime (no timezone)
		return serverZoned.toLocalDateTime();
	}

	private String writeToFile(MultipartFile file, File dir) {
		String originalName = file.getOriginalFilename();
		try {
			if (originalName == null || originalName.trim().isEmpty()) {
				originalName = "file_" + System.currentTimeMillis();
			}
			String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

			File destination = new File(dir, safeName);

			file.transferTo(destination);

			return safeName; // only filename stored in DB

		} catch (Exception e) {
			logger.error(originalName, e);
			return null; // return null if file save fails
		}
	}

	private List<String> parseRecipients(String jsonArrayStr) {
		List<String> list = new ArrayList<>();
		String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
		if (jsonArrayStr == null || jsonArrayStr.trim().isEmpty()) {
			return list;
		}
		JSONArray array = new JSONArray(jsonArrayStr);
		for (int i = 0; i < array.length(); i++) {
			String email = array.getString(i).trim();

			if (!email.matches(EMAIL_REGEX)) {
				System.err.println("Invalid email skipped: " + email);
				continue;
			}

			list.add(email);
		}

		return list;
	}

}
