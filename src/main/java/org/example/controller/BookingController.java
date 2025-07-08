package org.example.controller;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.command.CreateProcessInstanceCommandStep1;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import lombok.RequiredArgsConstructor;
import org.example.entity.CarRequest;
import org.example.repository.CarRequestRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/booking")
@RequiredArgsConstructor
public class BookingController {

    private final ZeebeClient zeebeClient;
    private final CarRequestRepository carRequestRepository;
    private final RestTemplate restTemplate = new RestTemplate(); // dùng để gọi HTTP API

    @PostMapping("/request")
    public ResponseEntity<Long> createRequest(@RequestBody Map<String, String> payload) {
        CarRequest carRequest = new CarRequest();
        carRequest.setCarName(payload.get("carName"));
        carRequest.setRole(payload.get("role"));
        carRequest.setPurpose(payload.get("otherInfo"));
        ProcessInstanceEvent result = zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId("book_approval")
                .latestVersion()
                .variables(payload)
                .send()
                .join();

        return ResponseEntity.ok(result.getProcessInstanceKey());
    }

    @PostMapping("/complete-task")
    public ResponseEntity<String> completeTask(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Map<String, String> request) {

        String taskId = request.get("taskId");
        String completedBy = request.get("completedBy");
        String approve = request.get("approve");

        if (taskId == null || completedBy == null || authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("taskId, completedBy, and Bearer token are required");
        }

        String accessToken = authorizationHeader.substring(7); // Remove "Bearer " prefix
        String url = "http://localhost:8088/v2/user-tasks/" + taskId + "/completion";

        // Tạo body gửi kèm biến completedBy
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("completedBy", completedBy);
        variables.put("approve", approve);
        payload.put("variables", variables);
//        payload.put("approve", approve);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to complete task: " + e.getMessage());
        }
    }

}
