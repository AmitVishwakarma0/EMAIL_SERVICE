/**
 * SmsController.java
 *
 * Unified endpoint for Telegram-based message creation.
 * Supports both quick text input and bulk file uploads,
 * along with media attachments, location, contact, and poll messages.
 *
 * @author  Vinod Yogi
 * @since   2025-11-07
 * @version 1.2
 */

package com.hti.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.EmailRequest;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;
import com.hti.model.ScheduleFilterRequest;
import com.hti.service.BatchService;
import com.hti.service.ScheduleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@Tag(name = "Email Scheduling API", description = "Unified endpoint to Schedule Email messages from direct input or uploaded files.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/email-schedule-service")
public class ScheduleController {

	private final ScheduleService batchService;

	// ---------------------------------------------------------------------
	// 🚀 Unified Send Message API
	// ---------------------------------------------------------------------

	/**
	 * Unified API for schedule Email messages.
	 *
	 * @param emailRequest The unified message request payload.
	 * @param ipAddress    IP address of the client making the request.
	 * @return ResponseEntity containing the batchId.
	 */
	@PostMapping(value = "/schedule", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Schedule Email messages ", description = """
			Unified API for sending Email messages.

			🔹 **Supported Input Modes**
			- JSON fields inside `emailRequest`
			- Multiple attachments via `attachmentList`

			🔹 **Usage Example**
			multipart/form-data:
			- emailRequest: { ... JSON ... }
			- attachmentList: file(s)
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = ScheduleEmailRequest.class)))
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Messages processed successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid input or missing required fields."),
			@ApiResponse(responseCode = "500", description = "Internal server error.") })
	public ResponseEntity<?> sendMessages(@ModelAttribute @Valid ScheduleEmailRequest scheduleRequest,
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true)@RequestHeader String ipAddress) 
	{
		String batchId = batchService.scheduleEmailMessages(scheduleRequest, username, ipAddress);
		System.out.println("Returning Batch Id generated: " + batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}

	// ---------------------------------------------------------------------
	// Batch Management APIs
	// ---------------------------------------------------------------------

	/**
	 * Fetches details of a specific batch entry for editing or inspection. Used by
	 * clients to retrieve batch configuration or progress information.
	 *
	 * Example: GET /email-service/edit?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to fetch
	 * @return Batch details as {@link EmailProcessResponse}
	 */
	@GetMapping("/edit")
	@Operation(summary = "Edit a Scheduled Batch", description = """
			Retrieve details of an existing Scheduled entry for editing or inspection.

			🔹 **Usage Example**
			`GET /email-schedule-service/edit?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Scheduled Batch entry retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or username", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> editBatch(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to edit", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		return ResponseEntity.ok(batchService.editSchedule(username, ipAddress, batchId));
	}


	/**
	 * Completely aborts a batch process and halts any ongoing message dispatch.
	 * Once aborted, the batch cannot be resumed.
	 *
	 * Example: DELETE /email-service/abort?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to abort
	 * @return Confirmation message upon successful abort
	 */
	@DeleteMapping("/abort")
	@Operation(summary = "Abort Scheduled Batch Entry", description = """
			Permanently stop processing of a batch entry. This action cannot be undone.

			🔹 **Usage Example**
			`DELETE /email-schedule-service/abort?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Scheduled Batch aborted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or already completed", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> abortBatch(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to abort", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		batchService.abortSchedule(username, ipAddress, batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}


	/**
	 * Update and Resumes a previously paused batch entry. Continues message
	 * dispatch from the last known checkpoint.
	 *
	 * Example: GET /email-service/updateAndResume
	 * 
	 * @param emailRequest The Updated Email Request payload.
	 * @param ipAddress    Client IP address making the request
	 * @return Confirmation message upon successful resume
	 */
	@PostMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Update Scheduled Batch Entry", description = """
			Update Scheduled batch entry.

			🔹 **Usage Example**
			`GET /email-schedule-service/update`
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = ScheduleEmailRequest.class)))
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Scheduled Batch updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or batch not paused", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> updateAndResume(@ModelAttribute @Valid ScheduleEmailRequest scheduleRequest,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true)@RequestHeader String ipAddress, 
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username) {
		batchService.updateSchedule(scheduleRequest, username, ipAddress);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", scheduleRequest.getBatchId()));
	}

	@GetMapping("/all-schedules")
	@Operation(summary = "Retrieve All Scheduled Batches", description = """
			Fetch all Scheduled batches created by the specified user.

			🔹 **Typical Use Cases**
			- View active, completed, or paused bulk campaigns.
			- Useful for dashboard summaries or campaign history.

			🔹 **Example Request**
			`GET /email-schedule-service/all-bulk`

			🔹 **Response**
			Returns batch summaries containing fields like batchId, status, totalCount, and createdAt.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Scheduled batches retrieved successfully.", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "400", description = "Invalid parameters or missing headers.", content = @Content(mediaType = "application/json", schema = @Schema())),
			@ApiResponse(responseCode = "500", description = "Internal server error.", content = @Content(mediaType = "application/json", schema = @Schema())) })
	public ResponseEntity<?> getAllBulk(@Valid @ModelAttribute ScheduleFilterRequest filterRequest,
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress) {
		List<EmailScheduleResponse> list = batchService.getAllScheduledBatches(username, ipAddress, filterRequest);
		System.out.println("Returning list size: " + list.size());
		return ResponseEntity.ok(list);
	}

}
